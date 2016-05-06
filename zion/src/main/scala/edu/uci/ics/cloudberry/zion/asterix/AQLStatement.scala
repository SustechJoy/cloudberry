package edu.uci.ics.cloudberry.zion.asterix

import edu.uci.ics.cloudberry.util.Logging
import edu.uci.ics.cloudberry.zion.model.Predicate
import org.joda.time.Interval
import org.joda.time.format.DateTimeFormat
import play.api.libs.ws.{WSClient, WSResponse}

import scala.concurrent.{ExecutionContext, Future}

class AQLStatement() {

}

object AQLStatement {

}


class AsterixConnection(wSClient: WSClient, url: String)(implicit ec: ExecutionContext) extends Logging {

  def post(aql: String): Future[WSResponse] = {
    log.info("AQL:" + aql)
    val f = wSClient.url(url).post(aql)
    f.onFailure(failureHandler(aql))
    f
  }

  protected def failureHandler(aql: String): PartialFunction[Throwable, Unit] = {
    case e: Throwable => log.error("WS Error:" + aql, e); throw e
  }
}
