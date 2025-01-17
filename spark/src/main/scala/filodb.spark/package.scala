package filodb

import akka.actor.ActorRef
import com.typesafe.config.Config
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.apache.spark.sql.{SQLContext, DataFrame, Row, Column => SparkColumn}
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.catalyst.expressions.Literal
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

import filodb.core._
import filodb.core.metadata.{Column, Dataset}
import filodb.core.reprojector.MemTable
import filodb.coordinator.{CoordinatorActor, RowSource}

package spark {
  case class DatasetNotFound(dataset: String) extends Exception(s"Dataset $dataset not found")
  // For each mismatch: the column name, DataFrame type, and existing column type
  case class ColumnTypeMismatch(mismatches: Set[(String, DataType, Column.ColumnType)]) extends Exception
  case class NoSortColumn(name: String) extends Exception(s"No sort column found $name")
  case class NoPartitionColumn(name: String) extends Exception(s"No partition column found $name")
  case class BadSchemaError(reason: String) extends Exception(reason)
}

/**
 * Provides base methods for reading from and writing to FiloDB tables/datasets.
 * Note that this is not the recommended DataFrame load/save API, please see DefaultSource.scala.
 * Configuration is done through setting SparkConf variables, like filodb.cassandra.keyspace
 * Here is how you could use these APIs
 *
 * {{{
 *   > import filodb.spark._
 *   > sqlContext.saveAsFiloDataset(myDF, "table1", "sortCol", "partCol", createDataset=true)
 *
 *   > sqlContext.filoDataset("table1")
 * }}}
 */
package object spark extends StrictLogging {
  val DefaultWriteTimeout = 999 minutes

  import CoordinatorActor._
  import filodb.spark.FiloRelation._
  import FiloSetup.{metaStore, memTable}

  private def ingestRddRows(coordinatorActor: ActorRef,
                            dataset: String,
                            columns: Seq[String],
                            version: Int,
                            rows: Iterator[Row]): Unit = {
    val ingestCmd = SetupIngestion(dataset, columns, version)
    actorAsk(coordinatorActor, ingestCmd, 10 seconds) {
      case IngestionReady =>
      case UnknownDataset => throw DatasetNotFound(dataset)
      case BadSchema(reason) => throw BadSchemaError(reason)
      case other: ErrorResponse => throw new RuntimeException(other.toString)
    }

    var linesIngested = 0
    rows.grouped(1000).foreach { lines =>
      val mappedLines = lines.toSeq.map(RddRowReader)
      var resp: MemTable.IngestionResponse = MemTable.PleaseWait
      do {
        resp = memTable.ingestRows(dataset, version, mappedLines)
        if (resp == MemTable.PleaseWait) {
          do {
            logger.info("Waiting for MemTable to be able to ingest again...")
            Thread sleep 10000
          } while (!memTable.canIngest(dataset, version))
        }
      } while (resp != MemTable.Ingested)
      linesIngested += mappedLines.length
      if (linesIngested % 25000 == 0) logger.info(s"Ingested $linesIngested lines!")
    }
    coordinatorActor ! Flush(dataset, version)
  }

  implicit class FiloContext(sqlContext: SQLContext) {

    /**
     * Creates a DataFrame from a FiloDB table.  Does no reading until a query is run, but it does
     * read the schema for the table.
     * @param dataset the name of the FiloDB table/dataset to read from
     * @param version the version number to read from
     */
    def filoDataset(dataset: String,
                    version: Int = 0,
                    minPartitions: Int = FiloRelation.DefaultMinPartitions): DataFrame =
      sqlContext.baseRelationToDataFrame(FiloRelation(dataset, version, minPartitions)(sqlContext))

    private def runCommands[B](cmds: Set[Future[Response]]): Unit = {
      val responseSet = Await.result(Future.sequence(cmds), 5 seconds)
      if (!responseSet.forall(_ == Success)) throw new RuntimeException(s"Some commands failed: $responseSet")
    }

    import filodb.spark.TypeConverters._

    private def checkAndAddColumns(df: DataFrame,
                                   dataset: String,
                                   version: Int): Unit = {
      // Pull out existing dataset schema
      val schema = parse(metaStore.getSchema(dataset, version)) { schema =>
        logger.info(s"Read schema for dataset $dataset = $schema")
        schema
      }

      // Translate DF schema to columns, create new ones if needed
      val namesTypes = df.schema.map { f => f.name -> f.dataType }.toMap
      val matchingCols = namesTypes.keySet.intersect(schema.keySet)
      val missingCols = namesTypes.keySet -- schema.keySet
      logger.info(s"Matching columns - $matchingCols\nMissing columns - $missingCols")

      // Type-check matching columns
      val matchingTypeErrs = matchingCols.collect {
        case colName: String if sqlTypeToColType(namesTypes(colName)) != schema(colName).columnType =>
          (colName, namesTypes(colName), schema(colName).columnType)
      }
      if (matchingTypeErrs.nonEmpty) throw ColumnTypeMismatch(matchingTypeErrs)

      if (missingCols.nonEmpty) {
        val addMissingCols = missingCols.map { colName =>
          val newCol = Column(colName, dataset, version, sqlTypeToColType(namesTypes(colName)))
          metaStore.newColumn(newCol)
        }
        runCommands(addMissingCols)
      }
    }

    // This doesn't create columns, because that's in checkAndAddColumns.  However, it
    // does check that the sortColumn and partitionColumn are in the DF.
    private def createNewDataset(datasetName: String,
                                 sortColumn: String,
                                 partitionColumn: String,
                                 df: DataFrame): Unit = {
      df.schema.find(_.name == sortColumn).getOrElse(throw NoSortColumn(sortColumn))
      df.schema.find(_.name == partitionColumn).getOrElse(throw NoPartitionColumn(partitionColumn))

      val dataset = Dataset(datasetName, sortColumn, partitionColumn)
      logger.info(s"Creating dataset $dataset...")
      actorAsk(FiloSetup.coordinatorActor, CreateDataset(dataset, Nil)) {
        case DatasetCreated =>
          logger.info(s"Dataset $datasetName created successfully...")
        case DatasetError(errMsg) =>
          throw new RuntimeException(s"Error creating dataset: $errMsg")
      }
    }

    /**
     * Saves a DataFrame in a FiloDB Table
     * - Creates columns in FiloDB from DF schema if needed
     *
     * @param df the DataFrame to write to FiloDB
     * @param dataset the name of the FiloDB table/dataset to read from
     * @param sortColumn the name of the column used as the sort primary key within each partition
     * @param partitionColumn one column specifically for partitioning.  If not specified, then
     *                        one global partition will be created for all the data, which is
     *                        probably not what you want, but easier for getting started.
     *          Partitioning columns could be created using an expression on another column
     *          {{{
     *            val newDF = df.withColumn("partition", df("someCol") % 100)
     *          }}}
     *          or even UDFs:
     *          {{{
     *            val idHash = sqlContext.udf.register("hashCode", (s: String) => s.hashCode())
     *            val newDF = df.withColumn("partition", idHash(df("id")) % 100)
     *          }}}
     * @param version the version number to write to
     * @param createDataset if true, then creates a Dataset if one doesn't exist.  Defaults to false to
     *                      prevent accidental table creation.
     * @param writeTimeout Maximum time to wait for write of each partition to complete
     */
    def saveAsFiloDataset(df: DataFrame,
                          dataset: String,
                          sortColumn: String,
                          partitionColumn: Option[String] = None,
                          version: Int = 0,
                          createDataset: Boolean = false,
                          writeTimeout: FiniteDuration = DefaultWriteTimeout): Unit = {
      val filoConfig = FiloSetup.configFromSpark(sqlContext.sparkContext)
      FiloSetup.init(filoConfig)

      // Create an extra column if partition column not specified
      val (partCol, df1) = partitionColumn.map { userPartCol =>
        (userPartCol, df)
      }.getOrElse {
        val df1 = df.withColumn("_partition", new SparkColumn(Literal("/0")))
        ("_partition", df1)
      }

      if (createDataset) createNewDataset(dataset, sortColumn, partCol, df1)
      checkAndAddColumns(df1, dataset, version)
      val dfColumns = df1.schema.map(_.name)

      // Do a sort by partitioncolumn so that partitions are on same node... we hope
      // val sortedDf = df1.sort(df1(partCol))  (note: this doesn't work yet)
      val sortedDf = df1
      val numPartitions = sortedDf.rdd.partitions.size
      logger.info(s"Saving ($dataset/$version) with sortColumn $sortColumn, " +
                  s"partitionColumn $partCol, $numPartitions partitions")

      // For each partition, start the ingestion
      sortedDf.rdd.mapPartitionsWithIndex { case (index, rowIter) =>
        // Everything within this function runs on each partition/executor, so need a local datastore & system
        FiloSetup.init(filoConfig)
        logger.info(s"Starting ingestion of DataFrame for dataset $dataset, partition $index...")
        ingestRddRows(FiloSetup.coordinatorActor, dataset, dfColumns, version, rowIter)
        Iterator.empty
      }.count()
    }
  }
}