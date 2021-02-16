package fix

import scalafix.v1._

import scala.meta.Token._
import scala.meta._

class v3_0_0 extends SemanticRule("v3_0_0") {
  /*
  TODO:
   - not found: type ConcurrentEffect
   */

  override def fix(implicit doc: SemanticDocument): Patch = {
    val Blocker_M = SymbolMatcher.normalized("cats/effect/Blocker.")
    val Blocker_delay_M = SymbolMatcher.exact("cats/effect/Blocker#delay().")
    val Bracket_guarantee_M = SymbolMatcher.exact("cats/effect/Bracket#guarantee().")
    val Bracket_uncancelable_M = SymbolMatcher.exact("cats/effect/Bracket#uncancelable().")
    val Concurrent_M = SymbolMatcher.normalized("cats/effect/Concurrent.")
    val ContextShift_M = SymbolMatcher.normalized("cats/effect/ContextShift.")
    val IO_M = SymbolMatcher.normalized("cats/effect/IO.")
    val Sync_S = Symbol("cats/effect/Sync#")

    Patch.replaceSymbols(
      "cats/effect/package.BracketThrow." -> "cats/effect/MonadCancelThrow.",
      "cats/effect/Bracket." -> "cats/effect/MonadCancel.",
      "cats/effect/IO.async()." -> "async_",
      "cats/effect/Async#async()." -> "async_",
      "cats/effect/IO.suspend()." -> "defer",
      "cats/effect/Sync#suspend()." -> "defer",
      "cats/effect/ResourceLike#parZip()." -> "both",
      "cats/effect/Resource.liftF()." -> "eval",
      "cats/effect/Timer." -> "cats/effect/Temporal.",
      "cats/effect/concurrent/Deferred." -> "cats/effect/Deferred.",
      "cats/effect/concurrent/Ref." -> "cats/effect/Ref.",
      "cats/effect/concurrent/Semaphore." -> "cats/effect/std/Semaphore."
    ) +
      doc.tree.collect {
        // Bracket#guarantee(a)(b) -> MonadCancel#guarantee(a, b)
        case t @ q"${Bracket_guarantee_M(_)}($a)($b)" =>
          fuseParameterLists(t, a, b)

        // Bracket#uncancelable(a) -> MonadCancel#uncancelable(_ => a)
        case q"${Bracket_uncancelable_M(_)}($a)" =>
          Patch.addLeft(a, "_ => ")

        // Blocker#delay[F, A] -> Sync[F].blocking
        case t @ Term.ApplyType(Blocker_delay_M(_), List(typeF, _)) =>
          Patch.addGlobalImport(Sync_S) +
            Patch.replaceTree(t, s"${Sync_S.displayName}[$typeF].blocking")

        // Blocker#delay -> Sync[F].blocking
        case t @ Term.Select(_, Blocker_delay_M(_)) =>
          t.synthetics match {
            case TypeApplyTree(_, UniversalType(_, TypeRef(_, symbol, _)) :: _) :: _ =>
              Patch.addGlobalImport(Sync_S) +
                Patch.replaceTree(t, s"${Sync_S.displayName}[${symbol.displayName}].blocking")
            case _ =>
              Patch.empty
          }

        case t @ ImporteeNameOrRename(Blocker_M(_)) =>
          Patch.removeImportee(t)

        case t @ ImporteeNameOrRename(ContextShift_M(_)) =>
          Patch.removeImportee(t)

        case d: Defn.Def =>
          removeParam(d, _.decltpe.exists(Blocker_M.matches)) +
            removeParam(d, _.decltpe.exists(ContextShift_M.matches)) +
            // implicit Concurrent[IO] ->
            removeParam(
              d,
              p =>
                p.mods.nonEmpty && p.decltpe.exists {
                  case Type.Apply(Concurrent_M(_), List(IO_M(_))) => true
                  case _                                          => false
                }
            )
      }.asPatch
  }

  private object ImporteeNameOrRename {
    def unapply(importee: Importee): Option[Name] =
      importee match {
        case Importee.Name(x)      => Some(x)
        case Importee.Rename(x, _) => Some(x)
        case _                     => None
      }
  }

  // tree @ f(param1)(param2) -> f(param1, param2)
  private def fuseParameterLists(tree: Tree, param1: Tree, param2: Tree): Patch =
    (param1.tokens.lastOption, param2.tokens.headOption) match {
      case (Some(lastA), Some(firstB)) =>
        val between =
          tree.tokens.dropWhile(_ != lastA).drop(1).dropRightWhile(_ != firstB).dropRight(1)
        val maybeParen1 = between.find(_.is[RightParen])
        val maybeParen2 = between.reverseIterator.find(_.is[LeftParen])
        (maybeParen1, maybeParen2) match {
          case (Some(p1), Some(p2)) =>
            val toAdd = if (lastA.end == p1.start && p1.end == p2.start) ", " else ","
            Patch.replaceToken(p1, toAdd) + Patch.removeToken(p2)
          case _ => Patch.empty
        }
      case _ => Patch.empty
    }

  // f(p1, p2, p3) -> f(p1, p3) if paramMatcher(p2)
  private def removeParam(d: Defn.Def, paramMatcher: Term.Param => Boolean)(implicit
      doc: SemanticDocument
  ): Patch = {
    d.paramss.find(_.exists(paramMatcher)) match {
      case None => Patch.empty
      case Some(params) =>
        params match {
          // There is only one parameter, so we're removing the complete parameter list.
          case param :: Nil =>
            cutUntilDelims(d, param, _.is[LeftParen], _.is[RightParen])
          case _ =>
            params.zipWithIndex.find { case (p, _) => paramMatcher(p) } match {
              case Some((p, idx)) =>
                // Remove the first parameter.
                if (idx == 0) {
                  if (p.mods.nonEmpty)
                    cutUntilDelims(d, p, _.is[KwImplicit], _.is[Comma], keepL = true)
                  else
                    cutUntilDelims(d, p, _.is[LeftParen], _.is[Ident], keepL = true, keepR = true)
                }
                // Remove the last parameter.
                else if (params.size == idx + 1)
                  cutUntilDelims(d, p, _.is[Comma], _.is[RightParen], keepR = true)
                // Remove inside the parameter list.
                else
                  cutUntilDelims(d, p, _.is[Comma], _.is[Comma], keepL = true)
              case None => Patch.empty
            }
        }
    }
  }

  private def cutUntilDelims(
      outer: Tree,
      inner: Tree,
      leftDelim: Token => Boolean,
      rightDelim: Token => Boolean,
      keepL: Boolean = false,
      keepR: Boolean = false
  ): Patch = {
    val innerTokens = inner.tokens
    (innerTokens.headOption, innerTokens.lastOption) match {
      case (Some(first), Some(last)) =>
        val outerTokens = outer.tokens
        val maybeDelimL = outerTokens.takeWhile(_ != first).reverseIterator.find(leftDelim)
        val maybeDelimR = outerTokens.takeRightWhile(_ != last).find(rightDelim)
        (maybeDelimL, maybeDelimR) match {
          case (Some(delimL), Some(delimR)) =>
            val toRemove = outerTokens
              .dropWhile(_ != delimL)
              .drop(if (keepL) 1 else 0)
              .dropRightWhile(_ != delimR)
              .dropRight(if (keepR) 1 else 0)
            Patch.removeTokens(toRemove)
          case _ => Patch.empty
        }
      case _ => Patch.empty
    }
  }
}
