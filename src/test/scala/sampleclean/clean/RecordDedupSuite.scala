package sampleclean.clean

import org.apache.spark.SparkContext._
import org.apache.spark.sql.catalyst.expressions.Row
import org.scalatest.FunSuite
import sampleclean.api.SampleCleanContext
import sampleclean.clean.algorithm.AlgorithmParameters
import sampleclean.clean.deduplication.{EntityResolution, RecordDeduplication}
import sampleclean.clean.deduplication.join.{BlockerMatcherJoinSequence, BroadcastJoin}
import sampleclean.clean.deduplication.matcher.AllMatcher
import sampleclean.clean.featurize.AnnotatedSimilarityFeaturizer
import sampleclean.clean.featurize.AnnotatedSimilarityFeaturizer.WeightedJaccardSimilarity
import sampleclean.clean.featurize.Tokenizer.DelimiterTokenizer


class RecordDedupSuite extends FunSuite with LocalSCContext {

  val colNames = (0 until 20).toList.map("col" + _.toString)
  val sampleTableName = "test_sample"
  val tok = new DelimiterTokenizer(" ")

  def defaultBM(scc: SampleCleanContext,sim: AnnotatedSimilarityFeaturizer): BlockerMatcherJoinSequence = {
    val bJoin = new BroadcastJoin(scc.getSparkContext(),sim,false)
    val matcher = new AllMatcher(scc, sampleTableName)
    new BlockerMatcherJoinSequence(scc, sampleTableName,bJoin,List(matcher))
  }

  test("exec"){
    withFullRecords (1,{ scc =>
      // Initialize algorithm
      val similarity = new WeightedJaccardSimilarity(colNames, scc.getTableContext(sampleTableName), tok, 0.5)
      val blockerMatcher = defaultBM(scc, similarity)

      val RD = new RecordDeduplication(scc, sampleTableName, blockerMatcher)
      RD.setTableParameters(sampleTableName)
      assert(RD.hashCol == 0)

      // Within exec() function
      val sampleTableRDD = scc.getCleanSample(sampleTableName)
      val fullTableRDD = scc.getFullTable(sampleTableName)
      assert(sampleTableRDD.count() == 201 && fullTableRDD.count() == 201)

      val filteredPairs = blockerMatcher.blockAndMatch(sampleTableRDD, fullTableRDD)
      assert(filteredPairs.count() == 100)

      val dupCounts = filteredPairs.map { case (fullRow, sampleRow) =>
        (sampleRow(RD.hashCol).asInstanceOf[String], 1)
      }
        .reduceByKey(_ + _)
        .map(x => (x._1, x._2 + 1))

      scc.updateTableDuplicateCounts(sampleTableName, dupCounts)
      assert(scc.getCleanSampleAttr(sampleTableName, "dup").filter(x => x.getInt(1) > 1).count() == 100)
    })
  }

  test("api"){
    withFullRecords (1,{ scc =>
      // Initialize algorithm

      var similarity = new WeightedJaccardSimilarity(colNames, scc.getTableContext(sampleTableName), tok, 0.5)
      var RD = new RecordDeduplication(scc, sampleTableName, defaultBM(scc, similarity))
      RD.setTableParameters(sampleTableName)
      assert(scc.getCleanSampleAttr(sampleTableName, "dup").filter(x => x.getInt(1) > 1).count() == 0)
      RD.exec()
      assert(scc.getCleanSampleAttr(sampleTableName, "dup").filter(x => x.getInt(1) > 1).count() == 100)

      scc.resetSample(sampleTableName)
      similarity = new WeightedJaccardSimilarity(colNames, scc.getTableContext(sampleTableName), tok, 0.51)
      RD = new RecordDeduplication(scc, sampleTableName, defaultBM(scc, similarity))
      RD.setTableParameters(sampleTableName)
      assert(scc.getCleanSampleAttr(sampleTableName, "dup").filter(x => x.getInt(1) > 1).count() == 0)
      RD.exec()
      assert(scc.getCleanSampleAttr(sampleTableName, "dup").filter(x => x.getInt(1) > 1).count() == 0)
    })

  }

  test("object"){
    withFullRecords(1,{scc =>

      var RD = RecordDeduplication.textAutomatic(scc, sampleTableName,colNames,0.5,false)
      assert(scc.getCleanSampleAttr(sampleTableName, "dup").filter(x => x.getInt(1) > 1).count() == 0)
      RD.exec()
      assert(scc.getCleanSampleAttr(sampleTableName, "dup").filter(x => x.getInt(1) > 1).count() == 100)

      scc.resetSample(sampleTableName)
      RD = RecordDeduplication.textAutomatic(scc, sampleTableName,colNames,0.51,false)
      assert(scc.getCleanSampleAttr(sampleTableName, "dup").filter(x => x.getInt(1) > 1).count() == 0)
      RD.exec()
      assert(scc.getCleanSampleAttr(sampleTableName, "dup").filter(x => x.getInt(1) > 1).count() == 0)
    })
  }

  test("variations in parameters"){
    withSingleAttribute(1, {scc =>
      var RD = RecordDeduplication.textAutomatic(scc,sampleTableName,colNames,1,false)
      RD.exec()
      scc.resetSample(sampleTableName)
      RD = RecordDeduplication.textAutomatic(scc,sampleTableName,colNames,0,false)
      RD.exec()
      scc.resetSample(sampleTableName)
      RD = RecordDeduplication.textAutomatic(scc,sampleTableName,colNames,0.0001,false)
      RD.exec()
      scc.resetSample(sampleTableName)
      RD = RecordDeduplication.textAutomatic(scc,sampleTableName,colNames,0.9999,false)
      RD.exec()

      scc.resetSample(sampleTableName)
      RD = RecordDeduplication.textAutomatic(scc,sampleTableName,colNames,1,true)
      RD.exec()
      scc.resetSample(sampleTableName)
      RD = RecordDeduplication.textAutomatic(scc,sampleTableName,colNames,0,true)
      RD.exec()
      scc.resetSample(sampleTableName)
      RD = RecordDeduplication.textAutomatic(scc,sampleTableName,colNames,0.0001,true)
      RD.exec()
      scc.resetSample(sampleTableName)
      RD = RecordDeduplication.textAutomatic(scc,sampleTableName,colNames,0.9999,true)
      RD.exec()
      
    })
  }

  test("overhead"){
    withFullRecords (1,{ scc =>
      scc.resetSample(sampleTableName)
      val params = new AlgorithmParameters()

      val t0 = System.nanoTime()
      val similarity = new WeightedJaccardSimilarity(colNames, scc.getTableContext(sampleTableName), tok, 0.5)
      val RD = new RecordDeduplication(scc, sampleTableName, defaultBM(scc, similarity))
      RD.setTableParameters(sampleTableName)
      val t1 = System.nanoTime()
      RD.exec()
      val t2 = System.nanoTime()
      assert(scc.getCleanSampleAttr(sampleTableName, "dup").filter(x => x.getInt(1) > 1).count() == 100)
      val t3 = System.nanoTime()

      val rowRDDLarge = scc.getSparkContext().textFile("./src/test/resources/csvJaccard100dups").map(x => Row.fromSeq(x.split(",", -1).toSeq))

      val t4 = System.nanoTime()
      val blocker = new WeightedJaccardSimilarity(colNames, scc.getTableContext(sampleTableName).drop(2), tok, 0.5)
      val bJoin = new BroadcastJoin(scc.getSparkContext(), blocker, false)
      assert(bJoin.join(rowRDDLarge, rowRDDLarge).count() == 100)
      val t5 = System.nanoTime()

      println("Exec() in algorithm lasted " + (t2 - t1).toDouble / 1000000000 + " seconds.")
      println("Whole cleaning algorithm lasted " + (t3 - t0).toDouble / 1000000000 + " seconds.")
      println("Join lasted " + (t5 - t4).toDouble / 1000000000 + " seconds.")
    })



  }
}
