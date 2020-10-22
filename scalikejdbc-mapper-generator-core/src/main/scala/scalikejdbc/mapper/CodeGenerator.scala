package scalikejdbc.mapper

import java.sql.JDBCType
import java.util.Locale
import java.util.Locale.ENGLISH

import scalikejdbc._

import scala.language.implicitConversions
/**
 * Active Record like template generator
 */
class CodeGenerator(table: Table, specifiedClassName: Option[String] = None)(implicit config: GeneratorConfig = GeneratorConfig())
  extends Generator with LoanPattern {

  import java.io.{ File, FileOutputStream, OutputStreamWriter }
  import java.sql.{ JDBCType => JavaSqlTypes }

  private val packageName = config.packageName
  private val className = specifiedClassName.getOrElse(config.tableNameToClassName(table.name))
  private val syntaxNameString = config.tableNameToSyntaxName(table.name)
  private val syntaxName = config.tableNameToSyntaxVariableName(table.name)
  private val comma = ","
  private val eol = config.lineBreak.value

  object TypeName {
    val Any = "Any"
    val AnyArray = "Array[Any]"
    val ByteArray = "Array[Byte]"
    val Long = "Long"
    val Boolean = "Boolean"
    val DateTime = "DateTime"
    val LocalDate = "LocalDate"
    val LocalTime = "LocalTime"
    val String = "String"
    val Byte = "Byte"
    val Int = "Int"
    val Short = "Short"
    val Float = "Float"
    val Double = "Double"
    val Blob = "Blob"
    val Clob = "Clob"
    val Ref = "Ref"
    val Struct = "Struct"
    val BigDecimal = "BigDecimal" // scala.math.BigDecimal
  }

  case class IndentGenerator(i: Int) {
    def indent: String = " " * i * 2
  }

  implicit def convertIntToIndentGenerator(i: Int): IndentGenerator = IndentGenerator(i)

  case class ColumnInScala(underlying: Column) {

    lazy val nameInScala: String = config.columnNameToFieldName(underlying.name)

    lazy val rawTypeInScala: String = {
      val Column(name, typ, typeName, size, notNull, autoIncrement, generated) = underlying
      println(s"name=$name, type=$typ, typeName=$typeName, size=$size, notNull=$notNull, autoIncrement=$autoIncrement, generated=$generated")
      config.columnNameToFieldType.lift((className, nameInScala)).getOrElse {
        config.columnTypeToFieldType.lift(underlying.dataTypeName).getOrElse {
          JDBCType.valueOf(underlying.dataType) match {
            case JavaSqlTypes.ARRAY => TypeName.AnyArray
            case JavaSqlTypes.BIGINT => TypeName.Long
            case JavaSqlTypes.BINARY => TypeName.ByteArray
            case JavaSqlTypes.BIT => TypeName.Boolean
            case JavaSqlTypes.BLOB => TypeName.Blob
            case JavaSqlTypes.BOOLEAN => TypeName.Boolean
            case JavaSqlTypes.CHAR => TypeName.String
            case JavaSqlTypes.CLOB => TypeName.Clob
            case JavaSqlTypes.DATALINK => TypeName.Any
            case JavaSqlTypes.DATE => TypeName.LocalDate
            case JavaSqlTypes.DECIMAL => TypeName.BigDecimal
            case JavaSqlTypes.DISTINCT => TypeName.Any
            case JavaSqlTypes.DOUBLE => TypeName.Double
            case JavaSqlTypes.FLOAT => TypeName.Float
            case JavaSqlTypes.INTEGER => TypeName.Int
            case JavaSqlTypes.JAVA_OBJECT => TypeName.Any
            case JavaSqlTypes.LONGVARBINARY => TypeName.ByteArray
            case JavaSqlTypes.LONGVARCHAR => TypeName.String
            case JavaSqlTypes.NULL => TypeName.Any
            case JavaSqlTypes.NUMERIC => TypeName.BigDecimal
            case JavaSqlTypes.OTHER => TypeName.Any
            case JavaSqlTypes.REAL => TypeName.Float
            case JavaSqlTypes.REF => TypeName.Ref
            case JavaSqlTypes.SMALLINT => TypeName.Short
            case JavaSqlTypes.STRUCT => TypeName.Struct
            case JavaSqlTypes.TIME => TypeName.LocalTime
            case JavaSqlTypes.TIMESTAMP => config.dateTimeClass.simpleName
            case JavaSqlTypes.TINYINT => TypeName.Byte
            case JavaSqlTypes.VARBINARY => TypeName.ByteArray
            case JavaSqlTypes.VARCHAR => TypeName.String
            case JavaSqlTypes.NVARCHAR => TypeName.String
            case JavaSqlTypes.NCHAR => TypeName.String
            case JavaSqlTypes.LONGNVARCHAR => TypeName.String
            case _ => TypeName.Any
          }
        }
      }
    }

    lazy val typeInScala: String = {
      if (underlying.isNotNull) rawTypeInScala
      else "Option[" + rawTypeInScala + "]"
    }

    lazy val defaultValueInScala: String =
      config.fieldTypeToDefaultValue.lift(underlying.typeInScala).getOrElse {
        underlying.typeInScala match {
          case TypeName.AnyArray => "Array[Any]()"
          case TypeName.Long => "1L"
          case TypeName.ByteArray => "Array[Byte]()"
          case TypeName.Boolean => "false"
          case TypeName.String => "\"MyString\""
          case TypeName.LocalDate => "LocalDate.now"
          case TypeName.BigDecimal => "new java.math.BigDecimal(\"1\")"
          case TypeName.Double => "0.1D"
          case TypeName.Float => "0.1F"
          case TypeName.Int => "123"
          case TypeName.Short => "123"
          case TypeName.DateTime => "DateTime.now"
          case TypeName.Byte => "1"
          case _ => "null"
        }
      }

    private[CodeGenerator] def isAny: Boolean = rawTypeInScala == TypeName.Any
  }

  /**
   * Create directory to put the source code file if it does not exist yet.
   */
  def mkdirRecursively(file: File): Unit = {
    val parent = file.getAbsoluteFile.getParentFile
    if (!parent.exists) mkdirRecursively(parent)
    if (!file.exists) file.mkdir()
  }

  implicit def convertColumnToColumnInScala(column: Column): ColumnInScala = ColumnInScala(column)

  private[this] def outputModelFile =
    new File(config.srcDir + "/" + packageName.replace(".", "/") + "/" + className + ".scala")

  private[this] def shouldBeSkipped: Boolean =
    config.tableNamesToSkip.contains(table.name.toLowerCase)

  /**
   * Write the source code if outputFile does not exists.
   */
  def writeModelIfNonexistentAndUnskippable(): Boolean = {
    if (outputModelFile.exists) {
      println("\"" + packageName + "." + className + "\"" + " already exists.")
      false
    } else if (shouldBeSkipped) {
      println("\"" + packageName + "." + className + "\"" + " is skipped by settings.")
      false
    } else {
      writeModel()
      true
    }
  }

  /**
   * Write the source code to outputFile.
   * It overwrites a file if it already exists.
   */
  def writeModel(): Unit = {
    mkdirRecursively(outputModelFile.getParentFile)
    using(new FileOutputStream(outputModelFile)) { fos =>
      using(new OutputStreamWriter(fos)) {
        writer =>
          writer.write(modelAll())
          println("\"" + packageName + "." + className + "\"" + " created.")
      }
    }
  }

  /**
   * Class part.
   *
   * {{{
   * case class Member(id: Long, name: String, description: Option[String])) {
   *   def save(): Member = Member.update(this)
   *   def destroy(): Unit = Member.delete(this)
   * }
   * }}}
   */
  def classPart: String = {
    val defaultAutoSession = if (config.defaultAutoSession) s" = ${className}.autoSession" else ""

    val constructorArgs = table.allColumns.map {
      c => 1.indent + c.nameInScala + ": " + c.typeInScala + (if (c.isNotNull) "" else " = None")
    }.mkString("," + eol)

    val baseTypes = {
      val types = config.tableNameToBaseTypes(table.name)
      if (types.isEmpty) ""
      else types.mkString("extends ", " with ", " ")
    }

    s"""case class ${className}(
      |${constructorArgs}) ${baseTypes}{
      |
      |  def save()(implicit session: DBSession$defaultAutoSession): ${className} = ${className}.save(this)(session)
      |
      |  def destroy()(implicit session: DBSession$defaultAutoSession): Int = ${className}.destroy(this)(session)
      |
      |}""".stripMargin + eol
  }

  /**
   * {{{
   * object Member {
   *   // ... as follows
   * }
   * }}}
   */
  def objectPart: String = {

    val allColumns = table.allColumns
    val pkColumns = if (table.primaryKeyColumns.isEmpty) allColumns else table.primaryKeyColumns

    val interpolationMapper = {
      if (config.autoConstruct) {
        s"""  def apply(${syntaxName}: SyntaxProvider[${className}])(rs: WrappedResultSet): ${className} = autoConstruct(rs, ${syntaxName})
        |  def apply(${syntaxName}: ResultName[${className}])(rs: WrappedResultSet): ${className} = autoConstruct(rs, ${syntaxName})
        |""".stripMargin
      } else {
        val _interpolationMapper = allColumns.map { c =>
          val method = if (c.isAny) {
            if (c.isNotNull) "any"
            else "anyOpt"
          } else "get"
          2.indent + c.nameInScala + s" = rs.$method(" + syntaxName + "." + c.nameInScala + ")"
        }
          .mkString(comma + eol)
        s"""  def apply(${syntaxName}: SyntaxProvider[${className}])(rs: WrappedResultSet): ${className} = apply(${syntaxName}.resultName)(rs)
        |  def apply(${syntaxName}: ResultName[${className}])(rs: WrappedResultSet): ${className} = new ${className}(
        |${_interpolationMapper}
        |  )""".stripMargin + eol
      }
    }

    /**
     * {{{
     * val autoSession = AutoSession
     * }}}
     */
    val autoSession = "  override val autoSession = AutoSession" + eol

    val defaultAutoSession = if (config.defaultAutoSession) " = autoSession" else ""

    val insertColumns: List[Column] = allColumns.filterNot { c =>
      table.autoIncrementColumns.exists(_.name == c.name) || table.generatedColumns.exists(_.name == c.name)
    }

    /**
     * {{{
     * def create(name: String, birthday: Option[LocalDate])(implicit session: DBSession = autoSession): Member = {
     *   val generatedKey = SQL("""
     *     insert into member (
     *       NAME,
     *       BIRTHDAY
     *     ) VALUES (
     *       /*'name*/'abc',
     *       /*'birthday*/'1958-09-06'
     *     )
     *   """).bindByName(
     *     "name" -> name,
     *     "birthday" -> birthday
     *   ).updateAndReturnGeneratedKey.apply()
     *
     *   Member(
     *     id = generatedKey,
     *     name = name,
     *     birthday = birthday
     *   )
     * }
     * }}}
     */
    val createMethod = {
      val placeHolderPart: String = config.template match {
        case GeneratorTemplate.interpolation =>
          // ${id}, ${name}
          insertColumns.map(c => 4.indent + "${" + c.nameInScala + "}").mkString(comma + eol)
        case GeneratorTemplate.queryDsl =>
          // id, name
          insertColumns.map { c =>
            4.indent +
              (if (c.isAny) "(column." + c.nameInScala + ", ParameterBinder(" + c.nameInScala + ", (ps, i) => ps.setObject(i, " + c.nameInScala + ")))"
              else "column." + c.nameInScala + " -> " + c.nameInScala)
          }.mkString(comma + eol)
      }

      // def create(
      1.indent + s"def create(" + eol +
        // id: Long, name: Option[String] = None)(implicit session DBSession = autoSession): ClassName = {
        insertColumns.map { c => 2.indent + c.nameInScala + ": " + c.typeInScala + (if (c.isNotNull) "" else " = None") }.mkString(comma + eol) +
        ")(implicit session: DBSession" + defaultAutoSession + "): " + className + " = {" + eol +
        // val generatedKey =
        2.indent + table.autoIncrementColumns.headOption.map(_ => "val generatedKey = ").getOrElse("") +
        (config.template match {
          case GeneratorTemplate.interpolation =>
            "sql\"\"\"" + eol + 3.indent + "insert into ${" + className + ".table} ("
          case GeneratorTemplate.queryDsl =>
            // withSQL { insert.into(User).columns(
            "withSQL {" + eol + 3.indent + "insert.into(" + className + ").namedValues("
        }) + eol +
        (config.template match {
          case GeneratorTemplate.interpolation =>
            insertColumns.map(c => 4.indent + "${" + "column." + c.nameInScala + "}").mkString(comma + eol) + eol + 3.indent + ") values (" + eol
          case GeneratorTemplate.queryDsl =>
            ""
        }) +
        placeHolderPart + eol + 3.indent + ")" + eol +
        (config.template match {
          case GeneratorTemplate.interpolation =>
            3.indent + "\"\"\"" + (if (table.autoIncrementColumns.nonEmpty) ".updateAndReturnGeneratedKey.apply()" else ".update.apply()")
          case GeneratorTemplate.queryDsl =>
            2.indent + (if (table.autoIncrementColumns.nonEmpty) "}.updateAndReturnGeneratedKey.apply()" else "}.update.apply()")
        }) +
        eol +
        eol +
        2.indent + className + "(" + eol +
        (if (table.autoIncrementColumns.nonEmpty)
          table.autoIncrementColumns.headOption.map { c =>
          3.indent + c.nameInScala +
            (c.typeInScala match {
              case TypeName.Byte => " = generatedKey.toByte,"
              case TypeName.Int => " = generatedKey.toInt,"
              case TypeName.Short => " = generatedKey.toShort,"
              case TypeName.Float => " = generatedKey.toFloat,"
              case TypeName.Double => " = generatedKey.toDouble,"
              case TypeName.String => " = generatedKey.toString,"
              case TypeName.BigDecimal => " = BigDecimal.valueOf(generatedKey),"
              case _ => " = generatedKey,"
            }) + eol
        }.getOrElse("")
        else
          "") +
        allColumns.map { c => 3.indent + c.nameInScala + " = " + c.nameInScala }.mkString(comma + eol) + ")" + eol +
        1.indent + "}" + eol
    }

    /**
     * {{{
     * def save(entity: Member)(implicit session: DBSession = autoSession): Member = {
     *   SQL("""
     *     update
     *       member
     *     set
     *       ID = /*'id*/123,
     *       NAME = /*'name*/'abc',
     *       BIRTHDAY = /*'birthday*/'1958-09-06'
     *     where
     *       ID = /*'id*/123
     * """).bindByName(
     *     "id" -> entity.id,
     *     "name" -> entity.name,
     *     "birthday" -> entity.birthday
     *   ).update.apply()
     *   entity
     * }
     * }}}
     */
    val saveMethod = {
      val updateColumns = allColumns.filterNot { c =>
        table.generatedColumns.exists(_.name == c.name)
      }
      val placeHolderPart: String = config.template match {
        case GeneratorTemplate.interpolation =>
          // ${column.id} = ${entity.id}, ${column.name} = ${entity.name}
          updateColumns.map(c => 4.indent + "${column." + c.nameInScala + "} = ${entity." + c.nameInScala + "}").mkString(comma + eol)
        case GeneratorTemplate.queryDsl =>
          updateColumns.map { c =>
            4.indent +
              (if (c.isAny) "(column." + c.nameInScala + ", ParameterBinder(entity." + c.nameInScala + ", (ps, i) => ps.setObject(i, entity." + c.nameInScala + ")))"
              else "column." + c.nameInScala + " -> entity." + c.nameInScala)
          }.mkString(comma + eol)
      }

      val wherePart = config.template match {
        case GeneratorTemplate.interpolation =>
          // ${column.id} = ${entity.id} and ${column.name} = ${entity.name}
          4.indent + pkColumns.map(pk => "${" + "column." + pk.nameInScala + "} = ${entity." + pk.nameInScala + "}").mkString(" and ")
        case GeneratorTemplate.queryDsl =>
          // .eq(column.id, entity.id).and.eq(column.name, entity.name)
          pkColumns.map(pk => ".eq(column." + pk.nameInScala + ", entity." + pk.nameInScala + ")").mkString(".and")
      }

      (config.template match {
        case GeneratorTemplate.interpolation =>
          s"""  def save(entity: ${className})(implicit session: DBSession$defaultAutoSession): ${className} = {
          |    sql\"\"\"
          |      update
          |        $${${className}.table}
          |      set
          |${placeHolderPart}
          |      where
          |${wherePart}
          |      \"\"\".update.apply()
          |    entity
          |  }"""
        case GeneratorTemplate.queryDsl =>
          s"""  def save(entity: ${className})(implicit session: DBSession$defaultAutoSession): ${className} = {
          |    withSQL {
          |      update(${className}).set(
          |${placeHolderPart}
          |      ).where${wherePart}
          |    }.update.apply()
          |    entity
          |  }"""
      }).stripMargin + eol
    }

    /**
     * {{{
     * def destroy(entity: Member)(implicit session: DBSession = autoSession): Int = {
     *   SQL("""delete from member where id = /*'id*/123""")
     *     .bindByName("id" -> entity.id)
     *     .update.apply()
     * }
     * }}}
     */
    val destroyMethod = {

      val wherePart: String = config.template match {
        case GeneratorTemplate.interpolation =>
          // ${column.id} = ${entity.id} and ${column.name} = ${entity.name}
          pkColumns.map(pk => "${" + "column." + pk.nameInScala + "} = ${entity." + pk.nameInScala + "}").mkString(" and ")
        case GeneratorTemplate.queryDsl =>
          // .eq(column.id, entity.id).and.eq(column.name, entity.name)
          pkColumns.map(pk => ".eq(column." + pk.nameInScala + ", entity." + pk.nameInScala + ")").mkString(".and")
      }

      (config.template match {
        case GeneratorTemplate.interpolation =>
          s"""  def destroy(entity: ${className})(implicit session: DBSession$defaultAutoSession): Int = {
          |    sql\"\"\"delete from $${${className}.table} where ${wherePart}\"\"\".update.apply()
          |  }"""
        case GeneratorTemplate.queryDsl =>
          s"""  def destroy(entity: ${className})(implicit session: DBSession$defaultAutoSession): Int = {
          |    withSQL { delete.from(${className}).where${wherePart} }.update.apply()
          |  }"""
      }).stripMargin + eol
    }

    /**
     * {{{
     * def find(id: Long): Option[Member] = {
     *   DB readOnly { implicit session =>
     *     SQL("""select * from member where id = /*'id*/123""")
     *       .bindByName("id" -> id).map(*).single.apply()
     *   }
     * }
     * }}}
     */
    val findMethod = {
      val argsPart = pkColumns.map(pk => pk.nameInScala + ": " + pk.typeInScala).mkString(", ")
      val wherePart = config.template match {
        case GeneratorTemplate.interpolation =>
          pkColumns.map(pk => s"$${${syntaxName}.${pk.nameInScala}} = $${${pk.nameInScala}}").mkString(" and ")
        case GeneratorTemplate.queryDsl =>
          pkColumns.map(pk => s".eq(${syntaxName}.${pk.nameInScala}, ${pk.nameInScala})").mkString(".and")
      }

      (config.template match {
        case GeneratorTemplate.interpolation =>
          s"""  def find(${argsPart})(implicit session: DBSession$defaultAutoSession): Option[${className}] = {
            |    sql\"\"\"select $${${syntaxName}.result.*} from $${${className} as ${syntaxName}} where ${wherePart}\"\"\"
            |      .map(${className}(${syntaxName}.resultName)).single.apply()
            |  }"""
        case GeneratorTemplate.queryDsl =>
          s"""  def find(${argsPart})(implicit session: DBSession$defaultAutoSession): Option[${className}] = {
            |    withSQL {
            |      select.from(${className} as ${syntaxName}).where${wherePart}
            |    }.map(${className}(${syntaxName}.resultName)).single.apply()
            |  }"""
      }).stripMargin + eol
    }

    val interpolationFindByMethod = {
      s"""  def findBy(where: SQLSyntax)(implicit session: DBSession$defaultAutoSession): Option[${className}] = {
        |    sql\"\"\"select $${${syntaxName}.result.*} from $${${className} as ${syntaxName}} where $${where}\"\"\"
        |      .map(${className}(${syntaxName}.resultName)).single.apply()
        |  }""".stripMargin + eol
    }

    val queryDslFindByMethod = {
      s"""  def findBy(where: SQLSyntax)(implicit session: DBSession$defaultAutoSession): Option[${className}] = {
        |    withSQL {
        |      select.from(${className} as ${syntaxName}).where.append(where)
        |    }.map(${className}(${syntaxName}.resultName)).single.apply()
        |  }""".stripMargin + eol
    }

    /**
     * {{{
     * def countAll(): Long = {
     *   DB readOnly { implicit session =>
     *     SQL("""select count(1) from member""")
     *       .map(rs => rs.long(1)).single.apply().get
     *   }
     * }
     * }}}
     */
    val countAllMethod =
      (config.template match {
        case GeneratorTemplate.interpolation =>
          s"""  def countAll()(implicit session: DBSession$defaultAutoSession): Long = {
            |    sql\"\"\"select count(1) from $${${className}.table}\"\"\".map(rs => rs.long(1)).single.apply().get
            |  }"""
        case GeneratorTemplate.queryDsl =>
          s"""  def countAll()(implicit session: DBSession$defaultAutoSession): Long = {
            |    withSQL(select(sqls.count).from(${className} as ${syntaxName})).map(rs => rs.long(1)).single.apply().get
            |  }"""
      }).stripMargin + eol

    val C = "C"
    val factoryParam = {
      if (config.returnCollectionType == ReturnCollectionType.Factory)
        s", $C: Factory[$className, $C[$className]]"
      else
        ""
    }
    val typeParam = {
      if (config.returnCollectionType == ReturnCollectionType.Factory)
        s"[$C[_]]"
      else
        ""
    }
    val returnType = config.returnCollectionType match {
      case ReturnCollectionType.List => "List"
      case ReturnCollectionType.Vector => "Vector"
      case ReturnCollectionType.Array => "Array"
      case ReturnCollectionType.Factory => C
    }

    val toResult = config.returnCollectionType match {
      case ReturnCollectionType.List => "list.apply()"
      case ReturnCollectionType.Vector => "collection.apply[Vector]()"
      case ReturnCollectionType.Array => "collection.apply[Array]()"
      case ReturnCollectionType.Factory => s"collection.apply[$C]()"
    }

    /**
     * {{{
     * def findAll(): List[Member] = {
     *   DB readOnly { implicit session =>
     *     SQL("""select * from member""").map(*).list.apply()
     *   }
     * }
     * }}}
     */
    val findAllMethod =
      (config.template match {
        case GeneratorTemplate.interpolation =>
          s"""  def findAll${typeParam}()(implicit session: DBSession${defaultAutoSession}${factoryParam}): $returnType[${className}] = {
            |    sql\"\"\"select $${${syntaxName}.result.*} from $${${className} as ${syntaxName}}\"\"\".map(${className}(${syntaxName}.resultName)).${toResult}
            |  }"""
        case GeneratorTemplate.queryDsl =>
          s"""  def findAll${typeParam}()(implicit session: DBSession${defaultAutoSession}${factoryParam}): $returnType[${className}] = {
            |    withSQL(select.from(${className} as ${syntaxName})).map(${className}(${syntaxName}.resultName)).${toResult}
            |  }"""
      }).stripMargin + eol

    val interpolationFindAllByMethod = {
      s"""  def findAllBy${typeParam}(where: SQLSyntax)(implicit session: DBSession${defaultAutoSession}${factoryParam}): $returnType[${className}] = {
        |    sql\"\"\"select $${${syntaxName}.result.*} from $${${className} as ${syntaxName}} where $${where}\"\"\"
        |      .map(${className}(${syntaxName}.resultName)).${toResult}
        |  }""".stripMargin + eol
    }

    val queryDslFindAllByMethod = {
      s"""  def findAllBy${typeParam}(where: SQLSyntax)(implicit session: DBSession${defaultAutoSession}${factoryParam}): $returnType[${className}] = {
        |    withSQL {
        |      select.from(${className} as ${syntaxName}).where.append(where)
        |    }.map(${className}(${syntaxName}.resultName)).${toResult}
        |  }""".stripMargin + eol
    }

    val interpolationCountByMethod = {
      s"""  def countBy(where: SQLSyntax)(implicit session: DBSession$defaultAutoSession): Long = {
        |    sql\"\"\"select count(1) from $${${className} as ${syntaxName}} where $${where}\"\"\"
        |      .map(_.long(1)).single.apply().get
        |  }""".stripMargin + eol
    }

    val queryDslCountByMethod = {
      s"""  def countBy(where: SQLSyntax)(implicit session: DBSession$defaultAutoSession): Long = {
        |    withSQL {
        |      select(sqls.count).from(${className} as ${syntaxName}).where.append(where)
        |    }.map(_.long(1)).single.apply().get
        |  }""".stripMargin + eol
    }

    /**
     * {{{
     * def batchInsert(entities: collection.Seq[Member])(implicit session: DBSession = autoSession): collection.Seq[Int] = {
     *   val params: collection.Seq[Seq[(String, Any)]] = entities.map(entity =>
     *     Seq(
     *       "id" -> entity.id,
     *       "name" -> entity.name,
     *       "birthday" -> entity.birthday))
     *   SQL("""insert into member (
     *     id,
     *     name,
     *     birthday
     *   ) values (
     *     {id},
     *     {name},
     *     {birthday}
     *   )""").batchByName(params.toSeq: _*).apply()
     * }
     * }}}
     */
    val batchInsertMethod = {
      val factory = {
        if (config.returnCollectionType == ReturnCollectionType.Factory)
          s", $C: Factory[Int, $C[Int]]"
        else
          ""
      }
//  def ->[A](value: A)(implicit ev: ParameterBinderFactory[A]): (SQLSyntax, ParameterBinder) = (this, ev(value))

      // def batchInsert=(
      1.indent + s"def batchInsert${typeParam}(entities: collection.Seq[" + className + "])(implicit session: DBSession" + defaultAutoSession + factory + s"): $returnType[Int] = {" + eol +
        2.indent + "def toParameterBinder[A](value: A)(implicit ev: ParameterBinderFactory[A]): ParameterBinder = ev(value)" + eol +
        2.indent + "val params: collection.Seq[Seq[(String, Any)]] = entities.map(entity =>" + eol +
        3.indent + "Seq(" + eol +
        insertColumns.map(c => 4.indent + "\"" + c.nameInScala.replace("`", "") + "\" -> toParameterBinder(entity." + c.nameInScala + ")").mkString(comma + eol) +
        "))" + eol +
        2.indent + "SQL(\"\"\"insert into " + table.name + "(" + eol +
        insertColumns.map(c => 3.indent + c.name.replace("`", "")).mkString(comma + eol) + eol +
        2.indent + ")" + " values (" + eol +
        insertColumns.map(c => 3.indent + "{" + c.nameInScala.replace("`", "") + "}").mkString(comma + eol) + eol +
        2.indent + ")\"\"\").batchByName(params.toSeq: _*).apply[" + returnType + "]()" + eol +
        1.indent + "}" + eol
    }

    val nameConverters: String = {
      def quote(str: String) = "\"" + str + "\""
      val customNameColumns = table.allColumns.collect {
        case column if GeneratorConfig.columnNameToFieldNameBasic(column.name) != column.nameInScala =>
          quote(column.nameInScala) -> quote(column.name)
      }.toMap
      if (customNameColumns.nonEmpty) {
        1.indent + s"override val nameConverters: Map[String, String] = ${customNameColumns} " + eol + eol
      } else {
        ""
      }
    }

    val isQueryDsl = config.template == GeneratorTemplate.queryDsl
    val baseTypes = {
      val types = config.tableNameToCompanionBaseTypes(table.name).map(_.replace("$className", className))
      if (types.isEmpty) ""
      else types.mkString("with ", " with ", " ")
    }

    "object " + className + " extends SQLSyntaxSupport[" + className + s"] ${baseTypes}{" + eol +
      table.schema.filterNot(_.isEmpty).map { schema =>
        eol + 1.indent + "override val schemaName = Some(\"" + schema + "\")" + eol
      }.getOrElse("") +
      nameConverters +
      eol +
      1.indent + "override val tableName = \"" + table.name + "\"" + eol +
      eol +
      1.indent + "override val columns = Seq(" + allColumns.map(c => c.name).mkString("\"", "\", \"", "\"") + ")" + eol +
      eol +
      interpolationMapper +
      eol +
      1.indent + "val " + syntaxName + " = " + className + ".syntax(\"" + syntaxNameString + "\")" + eol + eol +
      autoSession +
      eol +
      findMethod +
      eol +
      findAllMethod +
      eol +
      countAllMethod +
      eol +
      (if (isQueryDsl) queryDslFindByMethod else interpolationFindByMethod) +
      eol +
      (if (isQueryDsl) queryDslFindAllByMethod else interpolationFindAllByMethod) +
      eol +
      (if (isQueryDsl) queryDslCountByMethod else interpolationCountByMethod) +
      eol +
      createMethod +
      eol +
      batchInsertMethod +
      eol +
      saveMethod +
      eol +
      destroyMethod +
      eol +
      "}"
  }

  private val timeImport: String = {
    val timeClasses = Set(
      TypeName.LocalDate,
      TypeName.LocalTime) ++ DateTimeClass.all.map(_.simpleName)

    table.allColumns.map(_.rawTypeInScala).filter(timeClasses) match {
      case classes if classes.nonEmpty =>
        val distinct = classes.distinct
        val (bra, ket) = if (distinct.size == 1) ("", "") else ("{", "}")
        if (config.dateTimeClass == DateTimeClass.JodaDateTime) {
          "import org.joda.time." + bra + classes.distinct.mkString(", ") + ket + eol +
            "import scalikejdbc.jodatime.JodaParameterBinderFactory._" + eol +
            "import scalikejdbc.jodatime.JodaTypeBinder._" + eol
        } else {
          "import java.time." + bra + classes.distinct.mkString(", ") + ket + eol
        }
      case _ => ""
    }
  }

  def modelAll(): String = {
    val javaSqlImport = table.allColumns.flatMap {
      c =>
        c.rawTypeInScala match {
          case TypeName.Blob => Some("Blob")
          case TypeName.Clob => Some("Clob")
          case TypeName.Ref => Some("Ref")
          case TypeName.Struct => Some("Struct")
          case _ => None
        }
    } match {
      case classes if classes.nonEmpty => "import java.sql.{" + classes.distinct.mkString(", ") + "}" + eol
      case _ => ""
    }
    val compatImport =
      if (config.returnCollectionType == ReturnCollectionType.Factory) {
        "import scala.collection.compat._" + eol
      } else {
        ""
      }
    val additionalImport = config.additionalImports.map(i => s"import $i").mkString("", eol, eol)

    "package " + config.packageName + eol +
      eol +
      compatImport +
      "import scalikejdbc._" + eol +
      timeImport +
      javaSqlImport +
      additionalImport +
      eol +
      classPart + eol +
      eol +
      objectPart + eol
  }

  // -----------------------
  // Spec
  // -----------------------

  private[this] val specClassName = (if (config.abstractSpec) s"Abstract${className}" else className) + "Spec"

  private[this] def outputSpecFile =
    new File(config.testDir + "/" + packageName.replace(".", "/") + "/" + specClassName + ".scala")

  def writeSpecIfNotExist(code: Option[String]): Unit = {
    if (outputSpecFile.exists) {
      println("\"" + packageName + "." + specClassName + "\"" + " already exists.")
    } else {
      writeSpec(code)
    }
  }

  def writeSpec(code: Option[String]): Unit = {
    code.foreach { code =>
      mkdirRecursively(outputSpecFile.getParentFile)
      using(new FileOutputStream(outputSpecFile)) {
        fos =>
          using(new OutputStreamWriter(fos)) {
            writer =>
              writer.write(code)
              println("\"" + packageName + "." + specClassName + "\"" + " created.")
          }
      }
    }
  }

  def specAll(): Option[String] = {
    config.testTemplate match {
      case GeneratorTestTemplate.ScalaTestFlatSpec =>
        Some(replaceVariablesForTestPart(
          s"""package %package%
             |
             |import org.scalatest.flatspec.FixtureAnyFlatSpec
             |import org.scalatest.matchers.should.Matchers
             |import scalikejdbc.scalatest.AutoRollback
             |import scalikejdbc._
             |$timeImport
             |%additionalImport%
             |
             |%modifier%class %specClassName% extends FixtureAnyFlatSpec with Matchers with AutoRollback %baseTypes% {
             |  %syntaxObject%
             |
             |  behavior of "%className%"
             |
             |  it should "find by primary keys" in { implicit session =>
             |    val maybeFound = %className%.find(%primaryKeys%)
             |    maybeFound.isDefined should be(true)
             |  }
             |  it should "find by where clauses" in { implicit session =>
             |    val maybeFound = %className%.findBy(%whereExample%)
             |    maybeFound.isDefined should be(true)
             |  }
             |  it should "find all records" in { implicit session =>
             |    val allResults = %className%.findAll()
             |    allResults.size should be >(0)
             |  }
             |  it should "count all records" in { implicit session =>
             |    val count = %className%.countAll()
             |    count should be >(0L)
             |  }
             |  it should "find all by where clauses" in { implicit session =>
             |    val results = %className%.findAllBy(%whereExample%)
             |    results.size should be >(0)
             |  }
             |  it should "count by where clauses" in { implicit session =>
             |    val count = %className%.countBy(%whereExample%)
             |    count should be >(0L)
             |  }
             |  it should "create new record" in { implicit session =>
             |    val created = %className%.create(%createFields%)
             |    created should not be(null)
             |  }
             |  it should "save a record" in { implicit session =>
             |    val entity = %className%.findAll().head
             |    // TODO modify something
             |    val modified = entity
             |    val updated = %className%.save(modified)
             |    updated should not equal(entity)
             |  }
             |  it should "destroy a record" in { implicit session =>
             |    val entity = %className%.findAll().head
             |    val deleted = %className%.destroy(entity)
             |    deleted should be(1)
             |    val shouldBeNone = %className%.find(%primaryKeys%)
             |    shouldBeNone.isDefined should be(false)
             |  }
             |  it should "perform batch insert" in { implicit session =>
             |    val entities = %className%.findAll()
             |    entities.foreach(e => %className%.destroy(e))
             |    val batchInserted = %className%.batchInsert(entities)
             |    batchInserted.size should be >(0)
             |  }
             |}""".stripMargin + eol))
      case GeneratorTestTemplate.specs2unit =>
        Some(replaceVariablesForTestPart(
          s"""package %package%
             |
             |import scalikejdbc.specs2.mutable.AutoRollback
             |import org.specs2.mutable._
             |import scalikejdbc._
             |$timeImport
             |%additionalImport%
             |
             |%modifier%class %specClassName% extends Specification %baseTypes% {
             |
             |  "%className%" should {
             |
             |    %syntaxObject%
             |
             |    "find by primary keys" in new AutoRollback {
             |      val maybeFound = %className%.find(%primaryKeys%)
             |      maybeFound.isDefined should beTrue
             |    }
             |    "find by where clauses" in new AutoRollback {
             |      val maybeFound = %className%.findBy(%whereExample%)
             |      maybeFound.isDefined should beTrue
             |    }
             |    "find all records" in new AutoRollback {
             |      val allResults = %className%.findAll()
             |      allResults.size should be_>(0)
             |    }
             |    "count all records" in new AutoRollback {
             |      val count = %className%.countAll()
             |      count should be_>(0L)
             |    }
             |    "find all by where clauses" in new AutoRollback {
             |      val results = %className%.findAllBy(%whereExample%)
             |      results.size should be_>(0)
             |    }
             |    "count by where clauses" in new AutoRollback {
             |      val count = %className%.countBy(%whereExample%)
             |      count should be_>(0L)
             |    }
             |    "create new record" in new AutoRollback {
             |      val created = %className%.create(%createFields%)
             |      created should not beNull
             |    }
             |    "save a record" in new AutoRollback {
             |      val entity = %className%.findAll().head
             |      // TODO modify something
             |      val modified = entity
             |      val updated = %className%.save(modified)
             |      updated should not equalTo(entity)
             |    }
             |    "destroy a record" in new AutoRollback {
             |      val entity = %className%.findAll().head
             |      val deleted = %className%.destroy(entity) == 1
             |      deleted should beTrue
             |      val shouldBeNone = %className%.find(%primaryKeys%)
             |      shouldBeNone.isDefined should beFalse
             |    }
             |    "perform batch insert" in new AutoRollback {
             |      val entities = %className%.findAll()
             |      entities.foreach(e => %className%.destroy(e))
             |      val batchInserted = %className%.batchInsert(entities)
             |      batchInserted.size should be_>(0)
             |    }
             |  }
             |
             |}""".stripMargin + eol))
      case GeneratorTestTemplate.specs2acceptance =>
        Some(replaceVariablesForTestPart(
          s"""package %package%
             |
             |import scalikejdbc.specs2.AutoRollback
             |import org.specs2._
             |import scalikejdbc._
             |$timeImport
             |%additionalImport%
             |
             |%modifier%class %specClassName% extends Specification %baseTypes% { def is =
             |
             |  "The '%className%' model should" ^
             |    "find by primary keys"         ! autoRollback().findByPrimaryKeys ^
             |    "find by where clauses"        ! autoRollback().findBy ^
             |    "find all records"             ! autoRollback().findAll ^
             |    "count all records"            ! autoRollback().countAll ^
             |    "find all by where clauses"    ! autoRollback().findAllBy ^
             |    "count by where clauses"       ! autoRollback().countBy ^
             |    "create new record"            ! autoRollback().create ^
             |    "save a record"                ! autoRollback().save ^
             |    "destroy a record"             ! autoRollback().destroy ^
             |    "perform batch insert"         ! autoRollback().batchInsert ^
             |                                   end
             |
             |  case class autoRollback() extends AutoRollback {
             |    %syntaxObject%
             |
             |    def findByPrimaryKeys = this {
             |      val maybeFound = %className%.find(%primaryKeys%)
             |      maybeFound.isDefined should beTrue
             |    }
             |    def findBy = this {
             |      val maybeFound = %className%.findBy(%whereExample%)
             |      maybeFound.isDefined should beTrue
             |    }
             |    def findAll = this {
             |      val allResults = %className%.findAll()
             |      allResults.size should be_>(0)
             |    }
             |    def countAll = this {
             |      val count = %className%.countAll()
             |      count should be_>(0L)
             |    }
             |    def findAllBy = this {
             |      val results = %className%.findAllBy(%whereExample%)
             |      results.size should be_>(0)
             |    }
             |    def countBy = this {
             |      val count = %className%.countBy(%whereExample%)
             |      count should be_>(0L)
             |    }
             |    def create = this {
             |      val created = %className%.create(%createFields%)
             |      created should not beNull
             |    }
             |    def save = this {
             |      val entity = %className%.findAll().head
             |      // TODO modify something
             |      val modified = entity
             |      val updated = %className%.save(modified)
             |      updated should not equalTo(entity)
             |    }
             |    def destroy = this {
             |      val entity = %className%.findAll().head
             |      val deleted = %className%.destroy(entity) == 1
             |      deleted should beTrue
             |      val shouldBeNone = %className%.find(%primaryKeys%)
             |      shouldBeNone.isDefined should beFalse
             |    }
             |    def batchInsert = this {
             |      val entities = %className%.findAll()
             |      entities.foreach(e => %className%.destroy(e))
             |      val batchInserted = %className%.batchInsert(entities)
             |      batchInserted.size should be_>(0)
             |    }
             |  }
             |
             |}""".stripMargin + eol))
      case GeneratorTestTemplate(name) => None
    }
  }

  private def replaceVariablesForTestPart(code: String): String = {
    val isQueryDsl = config.template == GeneratorTemplate.queryDsl
    val pkColumns = if (table.primaryKeyColumns.isEmpty) table.allColumns else table.primaryKeyColumns
    code.replace("%package%", packageName)
      .replace("%additionalImport%", config.specAdditionalImports.map(i => s"import $i").mkString(eol))
      .replace("%modifier%", if (config.abstractSpec) "abstract " else "")
      .replace("%specClassName%", specClassName)
      .replace("%className%", className)
      .replace("%baseTypes%", {
        val types = config.tableNameToSpecBaseTypes(table.name).map(_.replace("$className", className))
        if (types.isEmpty) ""
        else types.mkString("with ", " with ", " ")
      })
      .replace("%primaryKeys%", pkColumns.map {
        c => c.defaultValueInScala
      }.mkString(", "))
      .replace(
        "%syntaxObject%",
        if (isQueryDsl) "val " + syntaxName + " = " + className + ".syntax(\"" + syntaxNameString + "\")" else "")
      .replace(
        "%whereExample%",
        if (isQueryDsl)
          pkColumns.headOption.map { c =>
          "sqls.eq(" + syntaxName + "." + c.nameInScala + ", " + c.defaultValueInScala + ")"
        }.getOrElse("")
        else
          pkColumns.headOption.map { c =>
            "sqls\"" + c.name + " = ${" + c.defaultValueInScala + "}\""
          }.getOrElse(""))
      .replace("%createFields%", table.allColumns.filter {
        c =>
          c.isNotNull && table.autoIncrementColumns.forall(_.name != c.name) && table.generatedColumns.forall(_.name != c.name)
      }.map {
        c =>
          c.nameInScala + " = " + c.defaultValueInScala
      }.mkString(", "))
  }

  // -----------------------
  // Arbitrary
  // -----------------------

  private[this] def outputArbitraryFile =
    new File(config.testDir + "/" + packageName.replace(".", "/") + "/" + className + "Arbitrary.scala")

  def writeArbitraryIfNotExist(code: Option[String]): Unit = {
    if (outputArbitraryFile.exists) {
      println("\"" + packageName + "." + className + "Arbitrary\"" + " already exists.")
    } else {
      writeSpec(code)
    }
  }

  def writeArbitrary(code: Option[String]): Unit = {
    code.foreach { code =>
      mkdirRecursively(outputArbitraryFile.getParentFile)
      using(new FileOutputStream(outputArbitraryFile)) {
        fos =>
          using(new OutputStreamWriter(fos)) {
            writer =>
              writer.write(code)
              println("\"" + packageName + "." + className + "Arbitrary\"" + " created.")
          }
      }
    }
  }

  def arbitraryTraitPart: String = {
    val toMethodName = (c: Column) => s"gen${c.nameInScala.head.toUpper}${c.nameInScala.tail}"

    def genMethodDefinitionsPart = {
      val stringUtil = if (table.allColumns.exists(_.rawTypeInScala == "String")) {
        Seq(
          1.indent + "def stringArbitrary(max: Int): Gen[String] =",
          2.indent + "Gen.choose[Int](1, max).flatMap(Gen.listOfN[Char](_, Gen.alphaChar)).map(_.mkString)").mkString("", eol, eol) + eol
      } else {
        ""
      }

      val optStringUtil = if (table.allColumns.exists(_.typeInScala == "Option[String]")) {
        Seq(
          1.indent + "def stringOptArbitrary(max: Int): Gen[Option[String]] =",
          2.indent + "Gen.option(stringArbitrary(max))").mkString("", eol, eol) + eol
      } else {
        ""
      }

      val methodDefinitions = table.allColumns.map { c =>
        val definition = c.typeInScala match {
          case "String" =>
            s"def ${toMethodName(c)}(size: Int): Gen[${c.typeInScala}] = stringArbitrary(size)"
          case "Option[String]" =>
            s"def ${toMethodName(c)}(size: Int): Gen[${c.typeInScala}] = stringOptArbitrary(size)"
          case _ =>
            s"def ${toMethodName(c)}(): Gen[${c.typeInScala}] = Arbitrary.arbitrary[${c.typeInScala}]"
        }
        1.indent + definition
      }.mkString(eol + eol)

      s"${stringUtil}${optStringUtil}${methodDefinitions}"
    }

    def arbitraryMethodPart = {
      val groupedColumns = table.allColumns.grouped(22).zipWithIndex.toList
      val forExpressions = groupedColumns.map {
        case (columns, index) =>
          val values = columns.map(_.nameInScala).mkString("(", ", ", ")")
          val enumerators = columns.map { c =>
            val param = c.rawTypeInScala match {
              case "String" => c.size.toString
              case _ => ""
            }
            s"${3.indent}${c.nameInScala} <- ${toMethodName(c)}($param)"
          }.mkString(eol)
          s"""
           |    val gen$index = for {
           |${enumerators}
           |    } yield $values
           |""".stripMargin
      }.mkString(eol)
      val enumerators = groupedColumns.map {
        case (_, index) =>
          s"${3.indent}value$index <- gen$index"
      }.mkString(eol)

      val applyArgs = (for {
        (cs, i) <- groupedColumns
        (c, j) <- cs.zipWithIndex
      } yield s"${3.indent}${c.nameInScala} = value$i._${j + 1}").mkString(", " + eol)

      s"""
         |  def arbitrary: Arbitrary[${className}] = Arbitrary {
         |${forExpressions}
         |    for {
         |${enumerators}
         |    } yield ${className} (
         |${applyArgs}
         |    )
         |  }
         |""".stripMargin
    }

    s"""trait ${className}Arbitrary {
       |${arbitraryMethodPart}
       |${genMethodDefinitionsPart}
       |}
       |""".stripMargin
  }

  def arbitraryAll(): Option[String] = {

    val scalaCheckImport =
      Seq(
        "import org.scalacheck.{Arbitrary, Gen}",
        "import org.scalacheck.Arbitrary._").mkString(eol, eol, eol)

    val compatImport =
      if (config.returnCollectionType == ReturnCollectionType.Factory) {
        "import scala.collection.compat._" + eol
      } else {
        ""
      }

    val additionalImport = config.arbitraryAdditionalImports.map(i => s"import $i").mkString(eol)

    val generator = "package " + config.packageName + eol +
      eol +
      compatImport + scalaCheckImport +
      timeImport + eol +
      additionalImport + eol +
      eol +
      arbitraryTraitPart + eol
    Some(generator)
  }
}

