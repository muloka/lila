package lila.relay

import org.joda.time.DateTime

import chess.format.pgn.{ Tag, Tags }
import lila.common.{ LilaException, Chronometer }
import lila.socket.Socket.Uid
import lila.study._

private final class RelaySync(
    studyApi: StudyApi,
    chapterRepo: ChapterRepo
) {

  private type NbMoves = Int

  def apply(relay: Relay, games: RelayGames): Fu[NbMoves] = studyApi byId relay.studyId flatMap {
    _ ?? { study =>
      for {
        chapters <- chapterRepo orderedByStudy study.id
        movesPerGame <- lila.common.Future.traverseSequentially(games) { game =>
          chapters.find(game.is) match {
            case Some(chapter) => updateChapter(study, chapter, game)
            case None => createChapter(study, game) flatMap { chapter =>
              chapters.find(_.isEmptyInitial).ifTrue(chapter.order == 2).?? { initial =>
                studyApi.deleteChapter(study.ownerId, study.id, initial.id, socketUid)
              } inject chapter.root.mainline.size
            }
          }
        }
        nbMoves = movesPerGame.foldLeft(0)(_ + _)
      } yield nbMoves
    }
  }

  private def updateChapter(study: Study, chapter: Chapter, game: RelayGame): Fu[NbMoves] =
    updateChapterTags(study, chapter, game) >>
      updateChapterTree(study, chapter, game)

  private def updateChapterTree(study: Study, chapter: Chapter, game: RelayGame): Fu[NbMoves] = {
    game.root.mainline.foldLeft(Path.root -> none[Node]) {
      case ((parentPath, None), gameNode) =>
        val path = parentPath + gameNode
        chapter.root.nodeAt(path) match {
          case None => parentPath -> gameNode.some
          case Some(existing) =>
            gameNode.clock.filter(c => !existing.clock.has(c)) ?? { c =>
              studyApi.doSetClock(
                userId = chapter.ownerId,
                study = study,
                position = Position(chapter, path),
                clock = c.some,
                uid = socketUid
              )
            }
            path -> none
        }
      case (found, _) => found
    } match {
      // fix mainline, and call it a day
      case (path, _) if !Path.isMainline(chapter.root, path) => studyApi.promote(
        userId = chapter.ownerId,
        studyId = study.id,
        position = Position(chapter, path).ref,
        toMainline = true,
        uid = socketUid
      ) inject 0
      case (path, None) => fuccess(0) // no new nodes were found
      case (path, Some(node)) => // append new nodes to the chapter
        lila.common.Future.fold(node.mainline)(Position(chapter, path)) {
          case (position, n) => studyApi.doAddNode(
            userId = position.chapter.ownerId,
            study = study,
            position = position,
            rawNode = n,
            uid = socketUid,
            opts = moveOpts.copy(clock = n.clock),
            relay = Chapter.Relay(
              path = position.path + n,
              lastMoveAt = DateTime.now.some
            ).some
          ) flatten s"Can't add relay node $position $node"
        } inject node.mainline.size
    }
  }

  private def updateChapterTags(study: Study, chapter: Chapter, game: RelayGame): Funit = {
    val gameTags = game.tags.value.foldLeft(Tags(Nil)) {
      case (newTags, tag) =>
        if (!chapter.tags(_ => tag.name).has(tag.value)) newTags + tag
        else newTags
    }
    val tags = game.end
      .ifFalse(gameTags(_.Result).isDefined)
      .filterNot(end => chapter.tags(_.Result).??(end.resultText ==))
      .fold(gameTags) { end =>
        gameTags + Tag(_.Result, end.resultText)
      }
    lila.common.Future.traverseSequentially(tags.value) { tag =>
      studyApi.setTag(
        userId = chapter.ownerId,
        studyId = study.id,
        lila.study.actorApi.SetTag(chapter.id, tag.name.name, tag.value),
        uid = socketUid
      )
    }.void
  }

  private def createChapter(study: Study, game: RelayGame): Fu[Chapter] =
    chapterRepo.nextOrderByStudy(study.id) flatMap { order =>
      val chapter = Chapter.make(
        studyId = study.id,
        name = Chapter.Name(s"${game.whiteName} - ${game.blackName}"),
        setup = Chapter.Setup(
          none,
          game.tags.variant | chess.variant.Variant.default,
          chess.Color.White
        ),
        root = game.root,
        tags = game.tags,
        order = order,
        ownerId = study.ownerId,
        practice = false,
        gamebook = false,
        conceal = none,
        relay = Chapter.Relay(
          path = game.root.mainlinePath,
          lastMoveAt = DateTime.now.some
        ).some
      )
      studyApi.doAddChapter(study, chapter, sticky = false, uid = socketUid) inject chapter
    }

  private val moveOpts = MoveOpts(
    write = true,
    sticky = false,
    promoteToMainline = true,
    clock = none
  )

  private val socketUid = Uid("")
}
