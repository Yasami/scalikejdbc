package scalikejdbc.mapper

case class Column(name: String, dataType: Int, dataTypeName: String, size: Int, isNotNull: Boolean, isAutoIncrement: Boolean, isGenerated: Boolean)

