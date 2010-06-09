package org.apache.activemq.store.cassandra.scala

import com.shorrockin.cascal.session._
import com.shorrockin.cascal.utils.Conversions._
import collection.jcl.Conversions._
import reflect.BeanProperty
import CassandraClient._
import CassandraClient.Id._
import org.apache.cassandra.utils.BloomFilter
import grizzled.slf4j.Logger
import org.apache.activemq.store.cassandra.{DestinationMaxIds => Max}
import org.apache.activemq.store.cassandra._
import org.apache.cassandra.thrift.NotFoundException
import java.util.concurrent.atomic.{AtomicLong, AtomicInteger}
import org.apache.activemq.command.{SubscriptionInfo, MessageId, ActiveMQDestination}
import collection.jcl.{ArrayList, HashSet, Set}
import com.shorrockin.cascal.model.{SuperColumn, StandardKey, Key, Column}

class CassandraClient() {
  @BeanProperty var cassandraHost: String = _
  @BeanProperty var cassandraPort: int = _
  @BeanProperty var cassandraTimeout: int = _

  val logger = Logger(this.getClass)

  protected var pool: SessionPool = null

  def start() = {
    val params = new PoolParams(20, ExhaustionPolicy.Fail, 500L, 6, 2)
    var hosts = Host(cassandraHost, cassandraPort, cassandraTimeout) :: Nil
    pool = new SessionPool(hosts, params, Consistency.Quorum)
  }

  def stop() = {
    pool.close
  }

  protected def withSession[E](block: Session => E): E = {
    val session = pool.checkout
    try {
      block(session)
    } finally {
      pool.checkin(session)
    }
  }

  def getDestinationCount(): int = {
    withSession {
      session =>
        session.get(KEYSPACE \ BROKER_FAMILY \ BROKER_KEY \ BROKER_DESTINATION_COUNT) match {
          case Some(x) =>
            x.value
          case None =>
            insertDestinationCount(0)
            0
        }
    }
  }

  def insertDestinationCount(count: int) = {
    withSession {
      session =>
        session.insert(KEYSPACE \ BROKER_FAMILY \ BROKER_KEY \ (BROKER_DESTINATION_COUNT, count))
    }
  }

  def getMessageIdFilterFor(destination: ActiveMQDestination, size: long): BloomFilter = {
    val filterSize = Math.max(size, 10000)
    val bloomFilter = BloomFilter.getFilter(filterSize, 0.01d);
    var start = ""
    val end = ""
    var counter: int = 0
    while (counter < filterSize) {
      withSession {
        session =>
          val cols = session.list(KEYSPACE \ MESSAGE_TO_STORE_ID_FAMILY \ destination, RangePredicate(start, end))
          cols.foreach(col => {
            bloomFilter.add(col.name)
            start = col.name
            counter = counter + 1;
          })

      }
    }
    bloomFilter
  }


  def createDestination(name: String, isTopic: boolean, destinationCount: AtomicInteger): boolean = {
    withSession {
      session =>
        session.get(KEYSPACE \ DESTINATIONS_FAMILY \ name \ DESTINATION_IS_TOPIC_COLUMN) match {
          case Some(x) =>
            logger.info({"Destination %s exists".format(name)})
            return false
          case None =>
            val topic = KEYSPACE \ DESTINATIONS_FAMILY \ name \ (DESTINATION_IS_TOPIC_COLUMN, isTopic)
            val maxStore = KEYSPACE \ DESTINATIONS_FAMILY \ name \ (DESTINATION_MAX_STORE_SEQUENCE_COLUMN, 0L)
            val queueSize = KEYSPACE \ DESTINATIONS_FAMILY \ name \ (DESTINATION_QUEUE_SIZE_COLUMN, 0)
            val destCount = KEYSPACE \ BROKER_FAMILY \ BROKER_KEY \ (BROKER_DESTINATION_COUNT, destinationCount.incrementAndGet)
            try {
              session.batch(Insert(topic) :: Insert(maxStore) :: Insert(queueSize) :: Insert(destCount))
              true
            } catch {
              case e: RuntimeException =>
                destinationCount.decrementAndGet
                throw e
            }

        }
    }
  }


  def getDestinations(): Set[ActiveMQDestination] = {
    val destinations = new HashSet[ActiveMQDestination]
    withSession {
      session =>
        session.list(KEYSPACE \ DESTINATIONS_FAMILY, KeyRange("", "", 10000)).foreach {
          case (key, colomns) => {
            destinations.add(key.value)
          }
        }

    }
    destinations
  }


  def deleteQueue(destination: ActiveMQDestination, destinationCount: AtomicInteger): Unit = {
    withSession {
      session =>
        val msgs = KEYSPACE \ MESSAGES_FAMILY \ destination
        val dest = KEYSPACE \ DESTINATIONS_FAMILY \ destination
        val mids = KEYSPACE \ MESSAGE_TO_STORE_ID_FAMILY \ destination
        val sids = KEYSPACE \ STORE_IDS_IN_USE_FAMILY \ destination
        val subs = KEYSPACE \\ SUBSCRIPTIONS_FAMILY \ destination
        val count = KEYSPACE \ BROKER_FAMILY \ BROKER_KEY \ (BROKER_DESTINATION_COUNT, destinationCount.decrementAndGet)
        try {
          session.batch(Delete(msgs) :: Delete(dest) :: Delete(mids) :: Delete(sids) :: Delete(subs) :: Insert(count))
        } catch {
          case e: RuntimeException =>
            destinationCount.incrementAndGet
            throw e
        }
    }

  }

  def deleteTopic(destination: ActiveMQDestination, destinationCount: AtomicInteger): Unit = {
    deleteQueue(destination, destinationCount);
  }

  def getMaxStoreId(): Max = {
    var max: Max = new Max(null, 0, 0)
    val destinations = getDestinations.size
    if (destinations == 0) {
      return max;
    }
    var storeVal: long = 0
    var broker: long = 0
    withSession {
      session =>
        session.list(KEYSPACE \ DESTINATIONS_FAMILY, new KeyRange("", "", 10000), ColumnPredicate(
          List(DESTINATION_MAX_STORE_SEQUENCE_COLUMN, DESTINATION_MAX_BROKER_SEQUENCE_COLUMN)
          ), Consistency.Quorum).foreach {
          case (key, columns) => {
            columns.foreach {
              col => {
                if (col.name == bytes(DESTINATION_MAX_STORE_SEQUENCE_COLUMN))
                  storeVal = col.value
                else if (col.name == bytes(DESTINATION_MAX_BROKER_SEQUENCE_COLUMN))
                  broker = col.value
              }
            }
          }
          if (storeVal > max.getMaxStoreId) {
            max = new Max(key.value, storeVal, broker)
          }
        }
    }
    max
  }

  def getStoreId(destination: ActiveMQDestination, id: MessageId): long = {
    withSession {
      session =>
        session.get(KEYSPACE \ MESSAGE_TO_STORE_ID_FAMILY \ destination \ id.toString) match {
          case Some(x) =>
            x.value
          case None =>
            logger.error({"Store Id not found in destination %s for id %s".format(destination, id)})
            throw new RuntimeException("Store Id not found");
        }
    }
  }

  def getMessage(destination: ActiveMQDestination, storeId: long): Array[byte] = {
    withSession {
      session =>
        session.get(KEYSPACE \ MESSAGES_FAMILY \ destination \ storeId) match {
          case Some(x) =>
            x.value
          case None =>
            logger.error({"Message Not Found for destination:%s id:%i".format(destination, storeId)})
            throw new NotFoundException;
        }
    }
  }

  def saveMessage(destination: ActiveMQDestination, id: long, messageId: MessageId, message: Array[byte], queueSize: AtomicLong, duplicateDetector: BloomFilter): Unit = {
    withSession {
      session =>
        if (duplicateDetector.isPresent(messageId.toString)) {
          session.get(KEYSPACE \ MESSAGE_TO_STORE_ID_FAMILY \ destination \ messageId.toString) match {
            case Some(x) => {
              logger.warn({"Duplicate Message Save recieved from broker for %s...ignoring".format(messageId)})
              return
            }
            case None =>
              logger.warn("NotFoundException while confirming duplicate, BloomFilter false positive, continuing")
          }
        }

        logger.debug({"Saving message with id:%d".format(id)});
        logger.debug({"Saving message with brokerSeq id:%d".format(messageId.getBrokerSequenceId())});

        val mesg = KEYSPACE \ MESSAGES_FAMILY \ destination \ (id, message)
        val destQ = KEYSPACE \ DESTINATIONS_FAMILY \ destination \ (DESTINATION_QUEUE_SIZE_COLUMN, queueSize.incrementAndGet)
        val destStore = KEYSPACE \ DESTINATIONS_FAMILY \ destination \ (DESTINATION_MAX_STORE_SEQUENCE_COLUMN, id)
        val destBrok = KEYSPACE \ DESTINATIONS_FAMILY \ destination \ (DESTINATION_MAX_BROKER_SEQUENCE_COLUMN, messageId.getBrokerSequenceId)
        val idx = KEYSPACE \ MESSAGE_TO_STORE_ID_FAMILY \ destination \ (messageId.toString, id)
        val storeId = KEYSPACE \ STORE_IDS_IN_USE_FAMILY \ destination \ (id, "")
        try {
          session.batch(Insert(mesg) :: Insert(destQ) :: Insert(destStore) :: Insert(destBrok) :: Insert(idx) :: Insert(storeId));
        } catch {
          case e: RuntimeException =>
            queueSize.decrementAndGet
            logger.error({"Exception saving message"}, e)
            throw e
        }

    }

  }

  def deleteMessage(destination: ActiveMQDestination, id: MessageId, queueSize: AtomicLong): Unit = {
    val col = getStoreId(destination, id);
    val dest = KEYSPACE \ DESTINATIONS_FAMILY \ destination \ (DESTINATION_QUEUE_SIZE_COLUMN, queueSize.decrementAndGet)
    val mes = KEYSPACE \ MESSAGES_FAMILY \ destination
    val store = KEYSPACE \ STORE_IDS_IN_USE_FAMILY \ destination
    val idx = KEYSPACE \ MESSAGE_TO_STORE_ID_FAMILY \ destination
    try {
      withSession {
        session =>
          session.batch(Delete(mes, ColumnPredicate(col :: Nil)) :: Delete(store, ColumnPredicate(col :: Nil)) :: Delete(idx, ColumnPredicate(id.toString :: Nil)) :: Insert(dest))
      }
    } catch {
      case e: RuntimeException =>
        queueSize.incrementAndGet
        logger.error({"Exception saving message"}, e)
        throw e
    }
  }

  def deleteAllMessages(destination: ActiveMQDestination, queueSize: AtomicLong): Unit = {
    withSession {
      session =>
        session.remove(KEYSPACE \ MESSAGES_FAMILY \ destination)
        queueSize.set(0)
    }
  }

  def getMessageCount(destination: ActiveMQDestination): long = {
    withSession {
      session =>
        session.get(KEYSPACE \ DESTINATIONS_FAMILY \ destination \ DESTINATION_QUEUE_SIZE_COLUMN) match {
          case Some(x) =>
            x.value
          case None =>
            throw new RuntimeException("Count not found for destination" + destination);
        }
    }
  }

  def recoverMessages(destination: ActiveMQDestination, batchPoint: AtomicLong, maxReturned: int): java.util.List[Array[byte]] = {
    var start: String = ""
    if (batchPoint.get != -1) {
      start = batchPoint.get
    }
    val end = ""
    val messages = new ArrayList[Array[byte]]
    recoverMessagesFromTo(destination, start, end, maxReturned, messages, maxReturned)
    messages
  }

  private def recoverMessagesFromTo(key: String, start: String, end: String, limit: int, messages: ArrayList[Array[byte]], messagelimit: int): Unit = {
    withSession {
      session =>
        val range = RangePredicate(Some(start), Some(end), Order.Ascending, Some(limit))
        session.list(KEYSPACE \ MESSAGES_FAMILY \ key, range, Consistency.Quorum).foreach {
          col =>
            if (messages.size < messagelimit) messages.add(col.value)
        }
    }
  }

  def addSubscription(destination: ActiveMQDestination, subscriptionInfo: SubscriptionInfo, ack: long): Unit = {
    withSession {
      session =>
        val supercolumnName = subscriptionSupercolumn(subscriptionInfo)
        val supercolumn = KEYSPACE \\ SUBSCRIPTIONS_FAMILY \ destination \ supercolumnName
        val subdest = supercolumn \ (SUBSCRIPTIONS_SUB_DESTINATION_SUBCOLUMN, subscriptionInfo.getSubscribedDestination)
        val ackcol = supercolumn \ (SUBSCRIPTIONS_LAST_ACK_SUBCOLUMN, ack)
        var list = Insert(subdest) :: Insert(ackcol)
        if (subscriptionInfo.getSelector != null) {
          val selcolopt = supercolumn \ (SUBSCRIPTIONS_SELECTOR_SUBCOLUMN, subscriptionInfo.getSelector)
          list.add(Insert(selcolopt))
        }
        session.batch(list)
    }
  }

  def lookupSubscription(destination: ActiveMQDestination, clientId: String, subscriptionName: String): SubscriptionInfo = {
    withSession {
      session =>
        var subscriptionInfo = new SubscriptionInfo
        subscriptionInfo.setClientId(clientId)
        subscriptionInfo.setSubscriptionName(subscriptionName)
        subscriptionInfo.setDestination(destination)
        var dtype: Byte = if (destination.isTopic) ActiveMQDestination.TOPIC_TYPE else ActiveMQDestination.QUEUE_TYPE
        session.get(KEYSPACE \\ SUBSCRIPTIONS_FAMILY \ destination \ getSubscriptionSuperColumnName(clientId, subscriptionName)) 

    }
  }

}

object CassandraClient {
  implicit def destinationKey(destination: ActiveMQDestination): String = {
    destination.getQualifiedName
  }

  implicit def destinationBytes(destination: ActiveMQDestination): Array[Byte] = {
    bytes(destinationKey(destination))
  }

  implicit def destinationFromKey(key: String): ActiveMQDestination = {
    ActiveMQDestination.createDestination(key, ActiveMQDestination.QUEUE_TYPE)
  }

  private def subscriptionSupercolumn(info: SubscriptionInfo): String = {
    return info.getClientId + SUBSCRIPTIONS_CLIENT_SUBSCRIPTION_DELIMITER + nullSafeGetSubscriptionName(info)
  }


  private def nullSafeGetSubscriptionName(info: SubscriptionInfo): String = {
    return if (info.getSubscriptionName != null) info.getSubscriptionName else SUBSCRIPTIONS_DEFAULT_SUBSCRIPTION_NAME
  }


  private def getSubscriptionSuperColumnName(clientId: String, subscriptionName: String): String = {
    return clientId + SUBSCRIPTIONS_CLIENT_SUBSCRIPTION_DELIMITER + (if (subscriptionName != null) subscriptionName else SUBSCRIPTIONS_DEFAULT_SUBSCRIPTION_NAME)
  }


  private def getSubscriberId(clientId: String, subscriptionName: String): String = {
    return getSubscriptionSuperColumnName(clientId, subscriptionName)
  }




  object Id {
    val KEYSPACE = "MessageStore"
    val BROKER_FAMILY = "Broker"
    val BROKER_KEY = "Broker"
    val BROKER_DESTINATION_COUNT = "destination-count"

    val DESTINATIONS_FAMILY = "Destinations"
    val DESTINATION_IS_TOPIC_COLUMN = "isTopic"
    val DESTINATION_MAX_STORE_SEQUENCE_COLUMN = "max-store-sequence"
    val DESTINATION_MAX_BROKER_SEQUENCE_COLUMN = "max-broker-sequence"
    val DESTINATION_QUEUE_SIZE_COLUMN = "queue-size"


    val MESSAGES_FAMILY = "Messages"

    val MESSAGE_TO_STORE_ID_FAMILY = "MessageIdToStoreId"

    val STORE_IDS_IN_USE_FAMILY = "StoreIdsInUse"


    val SUBSCRIPTIONS_FAMILY = "Subscriptions"
    val SUBSCRIPTIONS_SELECTOR_SUBCOLUMN = "selector"
    val SUBSCRIPTIONS_LAST_ACK_SUBCOLUMN = "lastMessageAck"
    val SUBSCRIPTIONS_SUB_DESTINATION_SUBCOLUMN = "subscribedDestination";



    /*Subscriptions Column Family Constants*/


    val SUBSCRIPTIONS_CLIENT_SUBSCRIPTION_DELIMITER: String = "~~~~~"
    val SUBSCRIPTIONS_DEFAULT_SUBSCRIPTION_NAME: String = "@NOT_SET@"


  }


}