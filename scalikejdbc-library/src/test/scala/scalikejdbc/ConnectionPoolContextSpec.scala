package scalikejdbc

import org.scalatest._
import org.scalatest.matchers._

object ConnectionPoolContextSpecUtils {

  Class.forName("org.h2.Driver")
  ConnectionPool.add('ConnectionPoolContextSpec, "jdbc:h2:mem:ConnectionPoolContextSpec", "", "")

  def createTable(tableName: String)(name: Any) = {
    NamedDB(name)(NoConnectionPoolContext) autoCommit { implicit s =>
      try {
        SQL("drop table " + tableName).execute.apply()
      } catch { case e => }
      SQL("create table " + tableName + " (id integer primary key, name varchar(30))").execute.apply()
    }
  }

  def insertData(tableName: String, num: Int)(name: Any) = {
    NamedDB(name)(NoConnectionPoolContext) localTx { implicit s =>
      (1 to num).foreach { n =>
        SQL("insert into " + tableName + " (id, name) values (?, ?)").bind(n, "name" + n).update.apply()
      }
    }
  }

  def dropTable(tableName: String)(name: Any) = {
    try {
      NamedDB(name)(NoConnectionPoolContext) autoCommit { implicit s =>
        SQL("drop table " + tableName).execute.apply()
      }
    } catch { case e => }
  }

}

trait NamedCPContextAsDefault {
  implicit val context = MultipleConnectionPoolContext(
    ConnectionPool.DEFAULT_NAME -> ConnectionPool.get(),
    'ConnectionPoolContextSpec -> ConnectionPool.get()
  )
}

class ConnectionPoolContextMixinSpec extends FlatSpec with ShouldMatchers with Settings with NamedCPContextAsDefault {

  import ConnectionPoolContextSpecUtils._

  val tableNamePrefix = "emp_CPContextSpec" + System.currentTimeMillis().toString.substring(8)

  behavior of "DB with ConnectionPoolContext(mixin)"

  it should "work with NamedConnectionPoolContext" in {
    val tableName = tableNamePrefix + "_withNamedCPContextMixin"
    try {
      createTable(tableName)(ConnectionPool.DEFAULT_NAME)
      createTable(tableName)('ConnectionPoolContextSpec)
      insertData(tableName, 6)(ConnectionPool.DEFAULT_NAME)

      val result1 = DB readOnly { implicit s =>
        SQL("select * from " + tableName).map(rs => rs.string("name")).list.apply()
      }
      result1.size should equal(6)

      val result11 = NamedDB(ConnectionPool.DEFAULT_NAME) readOnly { implicit s =>
        SQL("select * from " + tableName).map(rs => rs.string("name")).list.apply()
      }
      result11.size should equal(6)
      result1.zip(result11).foreach { case (a, b) => a should equal(b) }

      val result2 = NamedDB('ConnectionPoolContextSpec) readOnly { implicit s =>
        SQL("select * from " + tableName).map(rs => rs.string("name")).list.apply()
      }
      result2.size should equal(6)
      result1.zip(result2).foreach { case (a, b) => a should equal(b) }

    } finally {
      dropTable(tableName)(ConnectionPool.DEFAULT_NAME)
      dropTable(tableName)('ConnectionPoolContextSpec)
    }
  }

}

class ConnectionPoolContextSpec extends FlatSpec with ShouldMatchers with Settings {

  import ConnectionPoolContextSpecUtils._

  val tableNamePrefix = "emp_CPContextSpec" + System.currentTimeMillis().toString.substring(8)

  behavior of "DB with ConnectionPoolContext"

  it should "work with NoConnectionPoolContext" in {
    val tableName = tableNamePrefix + "_withNoCPContext"
    try {
      createTable(tableName)(ConnectionPool.DEFAULT_NAME)
      createTable(tableName)('ConnectionPoolContextSpec)
      insertData(tableName, 4)(ConnectionPool.DEFAULT_NAME)

      val result1 = DB readOnly { implicit s =>
        SQL("select * from " + tableName).map(rs => rs.string("name")).list.apply()
      }
      result1.size should equal(4)

      val result11 = NamedDB(ConnectionPool.DEFAULT_NAME)(NoConnectionPoolContext) readOnly { implicit s =>
        SQL("select * from " + tableName).map(rs => rs.string("name")).list.apply()
      }
      result11.size should equal(4)
      result1.zip(result11).foreach { case (a, b) => a should equal(b) }

      val result2 = NamedDB(ConnectionPool.DEFAULT_NAME)(NoConnectionPoolContext) readOnly { implicit s =>
        SQL("select * from " + tableName).map(rs => rs.string("name")).list.apply()
      }
      result2.size should equal(4)
      result1.zip(result2).foreach { case (a, b) => a should equal(b) }

    } finally {
      dropTable(tableName)(ConnectionPool.DEFAULT_NAME)
      dropTable(tableName)('ConnectionPoolContextSpec)
    }
  }

  it should "work with NamedConnectionPoolContext" in {
    val tableName = tableNamePrefix + "_withNamedCPContext"
    implicit val context = MultipleConnectionPoolContext(
      ConnectionPool.DEFAULT_NAME -> ConnectionPool.get(),
      'ConnectionPoolContextSpec -> ConnectionPool.get()
    )
    try {
      createTable(tableName)(ConnectionPool.DEFAULT_NAME)
      createTable(tableName)('ConnectionPoolContextSpec)
      insertData(tableName, 6)(ConnectionPool.DEFAULT_NAME)

      val result1 = DB readOnly { implicit s =>
        SQL("select * from " + tableName).map(rs => rs.string("name")).list.apply()
      }
      result1.size should equal(6)

      val result11 = NamedDB(ConnectionPool.DEFAULT_NAME) readOnly { implicit s =>
        SQL("select * from " + tableName).map(rs => rs.string("name")).list.apply()
      }
      result11.size should equal(6)
      result1.zip(result11).foreach { case (a, b) => a should equal(b) }

      val result2 = NamedDB('ConnectionPoolContextSpec) readOnly { implicit s =>
        SQL("select * from " + tableName).map(rs => rs.string("name")).list.apply()
      }
      result2.size should equal(6)
      result1.zip(result2).foreach { case (a, b) => a should equal(b) }

    } finally {
      dropTable(tableName)(ConnectionPool.DEFAULT_NAME)
      dropTable(tableName)('ConnectionPoolContextSpec)
    }
  }

}