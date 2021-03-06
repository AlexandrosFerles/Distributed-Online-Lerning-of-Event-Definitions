package utils

import logic.Examples.Example
import logic.Theory

/**
  * Created by nkatz on 9/13/16.
  */

object DataUtils {

  trait Data

  trait TrainingSet {
    val trainingSet: List[Data] = Nil
    val testingSet: List[Data] = Nil
    def isEmpty = trainingSet == Nil
  }

  class DataSet(val trainingSet: List[(Int, Int)], val testingSet: List[(Int, Int)])

  /*
  * Find the positives and negative intervals in the database for the particular HLE
  */
  object Interval {
    def apply(HLE: String, start: Int) = {
      new Interval(HLE,start,0)
    }
  }
  /*
  * This class in used only to represent intervals, which in turn
  * are used to fetch data from a CAVIAR (notice that the step is hard-coded) database.
  * This is only for experiments with CAVIAR, it's a valid data format to use,
  * i.e. it's an abstraction for data representation I'm gonna stick with.
  * */
  case class Interval(val HLE: String, val startPoint: Int, var endPoint: Int) extends Data {
    val step = 40
    //var endPoint = 0
    def length = List.range(startPoint,endPoint+step, step).length
  }

  /* Companion object for the DataAsIntervals class */
  object DataAsIntervals {
    def apply() = {
      new DataAsIntervals(Nil,Nil)
    }
  }

  class DataAsIntervals(override val trainingSet: List[Interval], override val testingSet: List[Interval]) extends TrainingSet

  class DataAsExamples(override val trainingSet: List[Example], override val testingSet: List[Example]) extends TrainingSet

  class ResultsContainer(val tps: Double, val fps: Double, val fns: Double,
                         val precision: Double, val recall: Double,
                         val fscore: Double, val theorySize: Double, val time: Double, val theory: Theory)


  /* Contains utilities for collecting statistics */

  object Stats {

    def getExampleStats(exmpls: List[Exmpl]) = {

      def append(x: List[String], y: List[String]) = {
        x ++ y.filter(p => !x.contains(p))
      }

      val (ratios, annotSizes, narSizes, totalSize, wholeAnnotation, wholeNarrative) =
        exmpls.foldLeft(List[Double](), List[Double](), List[Double](), List[Double](), List[String](), List[String]()) { (s, e) =>
          val (rats, annots, nars, total, a, n) = (s._1, s._2, s._3, s._4, s._5, s._6)
          val holdsAtoms = e.exmplWithInertia.annotation
          val narrativeAtoms = e.exmplWithInertia.narrative
          val holdsSize = holdsAtoms.size.toDouble
          val narrativeSize = narrativeAtoms.size.toDouble
          val t = holdsSize + narrativeSize
          val ratio = holdsSize / narrativeSize
          //(rats :+ ratio, annots :+ holdsSize, nars :+ narrativeSize, a ++ holdsAtoms, n ++ narrativeAtoms)
          (rats :+ ratio, annots :+ holdsSize, nars :+ narrativeSize, total :+ t, a, n)
        }
      val meanRatio = Utils.mean(ratios)
      val meanAnnotSize = Utils.mean(annotSizes)
      val meanNarrativeSize = Utils.mean(narSizes)
      val totalAnnotSize = List[String]() // wholeAnnotation.distinct.size
      val totalNarSize = List[String]() //wholeNarrative.distinct.size
      println(s"Mean total example size: ${Utils.mean(totalSize)}" +
        s"\nMean annotation size per example: $meanAnnotSize\nMean narrative size per example: $meanNarrativeSize\n" +
        s"Mean ratio (annot/nar) per example: $meanRatio\nTotal annotation size: $totalAnnotSize\n" +
        s"Total narrative size: $totalNarSize")
      (meanRatio, meanAnnotSize, meanNarrativeSize, totalAnnotSize, totalNarSize)
    }
  }






}
