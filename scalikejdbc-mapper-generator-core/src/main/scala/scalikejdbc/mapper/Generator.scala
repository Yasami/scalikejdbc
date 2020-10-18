package scalikejdbc.mapper

trait Generator {
  def modelAll(): String
  def writeModel(): Unit
  def writeModelIfNonexistentAndUnskippable(): Boolean
  def writeSpec(code: Option[String]): Unit
  def writeSpecIfNotExist(code: Option[String]): Unit
  def specAll(): Option[String]
  def writeArbitrary(code: Option[String]): Unit
  def writeArbitraryIfNotExist(code: Option[String]): Unit
  def arbitraryAll(): Option[String]
}
