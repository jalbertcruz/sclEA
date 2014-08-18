package pea

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorRef}
import ea._
import pea.ds.{EvaluatorsPool, ReproducersPool}

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}

class PoolManagerCEvals(problem: Problem, cEvaluations: Int, manager: ActorRef, eContext: ExecutionContextExecutor, allFufuresFinished: () => Unit) extends Actor {

  private[this] implicit val executionContext = eContext

  var migrantsDestiny: ActorRef = _
  var Evaluations: Int = _
  var Emigrations: Int = _

  val p2Rep = new ReproducersPool[TIndEval]()
  val p2Eval = new EvaluatorsPool[TIndividual](problem.getPop())

  override def receive: Receive = {
    case ('migrantsDestiny, mDestiny: ActorRef) =>
      migrantsDestiny = mDestiny

    case ('migrate, newMigrant: TIndEval) =>
      p2Rep.append(List(newMigrant))
      p2Rep.removeWorstN(1)
      Emigrations += 1

    case 'start =>
      val pResultObtained = Promise[TIndEval]()
      pResultObtained.future.onSuccess({
        case r => manager !('resultObtained, r, Evaluations, Emigrations)
      })
      Evaluations = 0
      var bestSolution = new TIndEval(null, -1)
      val nFutures = new AtomicInteger(0)
      def mkFuture(beginAction: => Any, endAction: (Any) => Unit, cond: => Boolean): Future[Any] = {
        val res = Future {
          beginAction
        }
        nFutures.incrementAndGet()
        res.onSuccess {
          case eResult: Any =>
            endAction(eResult)
            val nVal = nFutures.decrementAndGet()
            if (cond)
              mkFuture(beginAction, endAction, cond)
            else {
              if (!pResultObtained.isCompleted) {
                pResultObtained.success(bestSolution)
              }
              if (nVal == 0) {
                allFufuresFinished()
              }
            }
        }
        res
      }

      for (i <- 1 to problem.config.EvaluatorsCount)
        mkFuture({
          val inds2Eval = p2Eval.extractElements(problem.config.EvaluatorsCapacity)
          var evals = Evaluator.evaluate(inds2Eval.toList)
          evals = evals.sortWith(_.compareTo(_) > 0)
          evals
        }, (r1: Any) => {
          val eResult = r1.asInstanceOf[ArrayBuffer[TIndEval]]
          if (eResult.length > 0) {
            p2Rep.append(eResult)
            Evaluations += eResult.length
            if (bestSolution._2 < eResult(0)._2) {
              bestSolution = eResult(0)
            }
          }
        }, Evaluations < cEvaluations)


      for (i <- 1 to problem.config.ReproducersCount)
        mkFuture({
          val iEvals = p2Rep.extractElements(problem.config.ReproducersCapacity)
          val res = Reproducer.reproduce(iEvals.toList)
          res
        }, (r1: Any) => {
          val rResult = r1.asInstanceOf[TPopulation]
          if (rResult.length > 0) {
            p2Eval.append(rResult)
            if (problem.config.rand.nextInt(100) % 2 == 0 && bestSolution._1 != null) {
              migrantsDestiny !('migrate, bestSolution.clone())
            }
          }
        }, Evaluations < cEvaluations)

  }

}