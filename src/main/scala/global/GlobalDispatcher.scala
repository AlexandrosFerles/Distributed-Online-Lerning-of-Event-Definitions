package global

import oled.Result
import akka.actor.{PoisonPill, Props, Actor}
import com.mongodb.casbah.commons.MongoDBObject
import com.typesafe.scalalogging.LazyLogging
import app.Globals
import logic.{LogicUtils, Literal, Theory}
import utils.DataUtils.{DataAsIntervals, DataAsExamples, ResultsContainer, TrainingSet}
import utils._
import utils.Implicits._
import jep.Jep


/**
  * Created by nkatz on 28/2/2016.
  */

class GlobalDispatcher( val DB: Database,
                      trainingSets : List[DataAsIntervals],
                      val delta: Double,
                      val breakTiesThreshold: Double,
                      val postPruningThreshold: Double,
                      val minSeenExmpls: Double,
                      val repeatFor: Int,
                      val chunkSize: Int,
                      withInertia: Boolean,
                      withPostPruning: Boolean,
                      onlinePruning: Boolean,
                      data: TrainingSet,
                      val HLE: String,
                      handCraftedTheoryFile: String = "",
                      val kernelSet: Theory = Theory(),
                      globals: Globals) extends Actor with LazyLogging {

  val WHOLE_DATA_SET_VALE = 1000000000
  var size = 2 // two processes are started, one for learning the initiatedAt part and one for the terminatedAt
  var theories = List[(Theory,Double)]()
  var merged = Theory()
  var time = 0.0
  //var done = false

  def receive = {

    case "start" =>

      val initiatedParent = context.actorOf(Props(
        new InitiatorGlobal(trainingSets, DB, delta , breakTiesThreshold ,postPruningThreshold ,minSeenExmpls ,
          repeatFor, chunkSize, withInertia , withPostPruning,
          onlinePruning, data, HLE, kernelSet = kernelSet, globals = globals)
      ) , name = s"InitiatedFather")

      initiatedParent ! "go"


      val terminatedParent = context.actorOf(Props(
        new TerminatorGlobal(trainingSets, DB, delta , breakTiesThreshold ,postPruningThreshold ,minSeenExmpls ,
          repeatFor, chunkSize, withInertia , withPostPruning,
          onlinePruning, data, HLE, kernelSet = kernelSet, globals = globals)
      ) , name = s"TerminatedFather")

      terminatedParent ! "go"

    //*/
    case Result(theory,trainingTime) =>
      theories = theories :+ (theory,trainingTime)
      size -= 1
      sender ! PoisonPill // kill the child actor
      if(size == 0) {
        // merge the theories and do cross-validation
        val first = theories.head
        val second = theories.tail.head
        merged = first._1.clauses ++ second._1.clauses
        val theorySize = merged.clauses.foldLeft(0)((x,y) => x + y.body.length + 1)
        time = Math.max(first._2,second._2)

        //logger.info(s"theory picked: ${merged.tostring}")
        logger.info(s"Performing cross-validation for $HLE theory")

        //=============================================
        val crossValJep = new Jep()
        //crossValJep.runScript(GlobalValues.ASPHandler)
        //=============================================

        val (tps,fps,fns,precision,recall,fscore) =
          crossVal(merged ,DB, crossValJep, 1500*chunkSize, data=data, globals = globals)

        //logger.info(s"\ntps: $tps\nfps: $fps\nfns: $fns\nprecision: $precision\nrecall: $recall\nf-score: $fscore\ntraining time: " +
        //  s"$time\ntheory size: $theorySize")
        val merged_ = Theory(LogicUtils.compressTheory(merged.clauses))
        logger.info(s"\nDone.Theory for $HLE found:\n ${merged_.showWithStats}")
        crossValJep.close()

        context.parent ! new ResultsContainer(tps.toFloat,fps.toFloat,fns.toFloat,precision,recall,fscore,theorySize.toFloat,time,merged)

        //println("sent results, shutting down")
        //context.stop(self)
        //context.system.shutdown()
      }
  }


  /**
    *
    * Evaluate a hypothesis on the testing data
    *
    * @param t
    * @param DB
    * @param jep
    * @param testingSetSize this is used to drop the slice of the data on the which we didn't train
    *                       (so use them for testing). This works in conjunction with 1500
    *                       (see runWithDataChunks). E.g. if DB.size = 2000 and 1500 = 1000
    *                       then runWithDataChunks will take the first 1000 examples for training. So,
    *                       here, we'll drop the first 1000 and use the rest for testing.
    */
  def crossVal(t: Theory, DB: Database, jep: Jep, testingSetSize: Int, data: TrainingSet, handCraftedTheoryFile: String = "",
               globals: Globals) = {

    data match {
      case x: DataAsExamples =>
        val dataIterator =
          if (testingSetSize < WHOLE_DATA_SET_VALE) DB.collection.find().sort(MongoDBObject("exampleId" -> 1)).drop(testingSetSize)
          else DB.collection.find().sort(MongoDBObject("exampleId" -> 1))
        while (dataIterator.hasNext) {
          val e = dataIterator.next()
          val ee = new Exmpl(e)
          println(ee.id)
          evaluateTheory(t, ee, withInertia = true, jep, handCraftedTheoryFile, globals)
        }
      case x: DataAsIntervals =>
        val data = getTestingData
        while (data.hasNext) {
          val e = data.next()
          println(e.time)
          evaluateTheory(t, e, withInertia = true, jep, handCraftedTheoryFile, globals)
        }
      case _ => throw new RuntimeException("This logic is not implemented yet")
    }
    val stats = t.stats
    (stats._1, stats._2, stats._3, stats._4, stats._5, stats._6)
  }

  def getTestingData: Iterator[Exmpl] = {
    data match {
      case x: DataAsExamples =>
        data.asInstanceOf[DataAsExamples].testingSet.toIterator
      case x: DataAsIntervals =>
        CaviarUtils.getDataFromIntervals(DB,HLE,data.asInstanceOf[DataAsIntervals].testingSet,chunkSize, withChunking = false)
      case _ => throw new RuntimeException("This logic is not implemented yet")
    }
  }

  def evaluateTheory(theory: Theory, e: Exmpl, withInertia: Boolean = true, jep: Jep, handCraftedTheoryFile: String = "", globals: Globals): Unit = {
    val varbedExmplPatterns = globals.EXAMPLE_PATTERNS_AS_STRINGS
    val coverageConstr = s"${globals.TPS_RULES}\n${globals.FPS_RULES}\n${globals.FNS_RULES}"
    val t =
      if(theory != Theory()) {
        theory.clauses.map(x => x.withTypePreds(globals).tostring).mkString("\n")
      } else {
        globals.INCLUDE_BK(handCraftedTheoryFile)
      }

    val show = globals.SHOW_TPS_ARITY_1 + globals.SHOW_FPS_ARITY_1 + globals.SHOW_FNS_ARITY_1
    val ex = if(withInertia) e.exmplWithInertia.tostring else e.exmplNoInertia.tostring
    val program = ex + globals.INCLUDE_BK(globals.BK_CROSSVAL) + t + coverageConstr + show
    val f = Utils.getTempFile("isConsistent",".lp",deleteOnExit = true)
    Utils.writeLine(program, f, "overwrite")
    //val answerSet = ASP.solve(task = Globals.INFERENCE, aspInputFile = f, jep=jep)
    val answerSet = ASP.solveWithJep(task = Globals.INFERENCE, aspInputFile = f, jep=jep)
    if (answerSet.nonEmpty) {
      val atoms = answerSet.head.atoms
      atoms.foreach { a=>
        val lit = Literal.toLiteral(a)
        val inner = lit.terms.head
        lit.functor match {
          case "tps" => theory.tps += inner.tostring
          case "fps" => theory.fps += inner.tostring
          case "fns" => theory.fns += inner.tostring
        }
      }
    }
  }


}