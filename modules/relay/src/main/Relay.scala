package lila.relay

import org.joda.time.DateTime

import lila.study.{ Study }
import lila.user.User

case class Relay(
    _id: Relay.Id,
    name: String,
    description: String,
    sync: Relay.Sync,
    ownerId: User.ID,
    likes: Study.Likes,
    startsAt: Option[DateTime],
    finishedAt: Option[DateTime],
    createdAt: DateTime
) {

  def id = _id

  def studyId = Study.Id(id.value)

  def slug = {
    val s = lila.common.String slugify name
    if (s.isEmpty) "-" else s
  }

  def setSync(v: Boolean) = copy(sync = sync set v)

  def finished = finishedAt.isDefined

  override def toString = s"""relay #$id "$name" $sync"""
}

object Relay {

  case class Id(value: String) extends AnyVal with StringValue

  def makeId = Id(ornicar.scalalib.Random nextString 8)

  case class Sync(
      upstream: Sync.Upstream,
      until: Option[DateTime],
      nextAt: Option[DateTime],
      delay: Option[Int] = None,
      log: SyncLog
  ) {

    def seconds: Option[Int] = until map { until =>
      (until.getSeconds - nowSeconds).toInt
    } filter (0<)

    def set(v: Boolean) = copy(
      until = v option DateTime.now.plusHours(3),
      nextAt = v option DateTime.now.plusSeconds(1)
    )

    override def toString = upstream.toString
  }

  object Sync {
    sealed abstract class Upstream(val key: String, val url: String) {
      override def toString = s"$key $url"
    }
    object Upstream {
      case class DgtOneFile(fileUrl: String) extends Upstream("dgt-one", fileUrl)
      case class DgtManyFiles(dirUrl: String) extends Upstream("dgt-many", dirUrl)
    }
  }

  case class WithStudy(relay: Relay, study: Study)

  case class WithStudyAndLiked(relay: Relay, study: Study, liked: Boolean)

  case class Selection(
      created: List[WithStudyAndLiked],
      started: List[WithStudyAndLiked],
      closed: List[WithStudyAndLiked]
  )
}
