package release

import java.io.{File, PrintStream}

import release.PomMod.Gav
import release.Starter.{PreconditionsException, TermOs}

object Release {

  def readFromPrompt(workDirFile: File, out: PrintStream, err: PrintStream, rebaseFn: () ⇒ Unit, branch: String, sgit: Sgit,
                     dependencyUpdates: Boolean, termOs: TermOs, shellWidth: Int, releaseToolGit: Sgit): Seq[Unit] = {
    if (sgit.hasLocalChanges) {
      val changes = sgit.localChanges().take(5)
      val changesOut = changes match {
        case c if c.size <= 5 ⇒ c.mkString("\n")
        case c ⇒ c.mkString("\n") + "\n..."
      }
      throw new PreconditionsException("Your branch: \"" + branch + "\" has local changes, please commit or reset\n" + changesOut)
    }
    rebaseFn.apply()
    Starter.registerExitFn("cleanup branches", () ⇒ {
      sgit.checkout(sgit.currentBranch)
    })
    sgit.checkout(branch)
    Starter.chooseUpstreamIfUndef(out, sgit, branch)
    val mod = PomMod(workDirFile)

    if (dependencyUpdates) {
      val showUpdates = Term.readFromYes(out, "Show dependency updates?")
      if (showUpdates == "y") {
        mod.showDependecyUpdates(shellWidth, termOs, out)
      }
    }

    val newMod = offerAutoFixForReleaseSnapshots(out, mod, shellWidth)
    if (newMod.hasNoShopPom) {
      out.println("---------")
      out.println("1. MAJOR version when you make incompatible API changes,")
      out.println("2. MINOR version when you add functionality in a backwards-compatible manner, and")
      out.println("3. PATCH version when you make backwards-compatible bug fixes.")
      out.println("   see also: http://semver.org/")
      out.println("---------")
    }

    val release = Term.readChooseOneOfOrType(out, "Enter the release version", newMod.suggestReleaseVersion(sgit.branchNamesLocal()))
    val releaseWitoutSnapshot = release.replaceFirst("-SNAPSHOT$", "")
    val nextReleaseWithoutSnapshot = Term.readFrom(out, "Enter the next version without -SNAPSHOT", newMod.suggestNextRelease(release))

    val nextSnapshot = nextReleaseWithoutSnapshot + "-SNAPSHOT"
    if (newMod.selfVersion != nextSnapshot) {
      newMod.changeVersion(nextSnapshot)
    }

    val releaseBrachName = "release/" + releaseWitoutSnapshot
    sgit.createBranch(releaseBrachName)
    if (newMod.selfVersion != nextSnapshot) {
      newMod.writeTo(workDirFile)
    }
    val toolSh1 = releaseToolGit.headStatusValue()
    val headCommitId = sgit.commitIdHead()
    val releaseMod = PomMod(workDirFile)
    if (sgit.hasNoLocalChanges) {
      out.println("skipped release commit on " + branch)
    } else {
      out.print("Committing pom changes ..")
      sgit.doCommitPomXmlsAnd(
        """[ishop-release] prepare for next iteration - %s
          |
          |Signed-off-by: Ishop-Dev-Infra <ishop-dev-infra@novomind.com>
          |Releasetool-sign: %s
          |Releasetool-sha1: %s""".stripMargin.format(nextReleaseWithoutSnapshot, Starter.sign(), toolSh1), releaseMod.depTreeFilenameList())

      out.println(". done")
    }
    out.print("Checking out " + releaseBrachName + " ..")
    sgit.checkout(releaseBrachName)
    out.println(". done")

    if (releaseMod.selfVersion != release) {
      releaseMod.changeVersion(release)
      releaseMod.writeTo(workDirFile)
    }

    if (sgit.hasNoLocalChanges) {
      out.println("skipped release commit on " + releaseBrachName)
    } else {
      out.print("Commiting pom changes ..")
      sgit.doCommitPomXmlsAnd(
        """[ishop-release] perform to - %s
          |
          |Signed-off-by: Ishop-Dev-Infra <ishop-dev-infra@novomind.com>
          |Releasetool-sign: %s
          |Releasetool-sha1: %s""".stripMargin.format(release, Starter.sign(), toolSh1), releaseMod.depTreeFilenameList())
      out.println(". done")
    }
    if (releaseMod.hasNoShopPom) {
      sgit.doTag(release)
    }
    out.print("Checking out " + branch + " ..")
    sgit.checkout(branch)
    out.println(". done")
    if (newMod.hasNoShopPom) {
      sgit.deleteBranch(releaseBrachName)
    }
    out.println(sgit.graph())
    val sendToGerrit = Term.readFromOneOfYesNo(out, "Send to Gerrit?")
    val selectedBranch = sgit.findUpstreamBranch().getOrElse(branch)
    if (sendToGerrit == "y") {

      if (sgit.hasChangesToPush) {
        val result = sgit.pushFor(srcBranchName = branch, targetBranchName = selectedBranch)
        if (newMod.hasNoShopPom) {
          sgit.pushTag(release)

        }
        // try to notify jenkins about tag builds
      }
      if (newMod.hasShopPom) {
        val result = sgit.pushHeads(srcBranchName = "release/" + releaseWitoutSnapshot,
          targetBranchName = "release/" + releaseWitoutSnapshot)
        if (newMod.hasNoShopPom) {
          sgit.pushTag(release)
        }
        // TODO try to trigger job updates for jenkins
        // TODO try to trigger job execution in loop with abort
      }
      out.println("done.")
    } else {
      val pushTagOrBranch = if (newMod.hasNoShopPom) {
        "git push origin tag v" + releaseWitoutSnapshot + "; "
      } else {
        "git push -u origin release/" + releaseWitoutSnapshot + ":release/" + releaseWitoutSnapshot + ";"
      }
      val deleteTagOrBranch = if (newMod.hasNoShopPom) {
        "git tag -d v" + release + "; "
      } else {
        "git branch -D release/" + releaseWitoutSnapshot + ";"
      }
      out.println(
        ("""commands for local rollback:
           |  git reset --hard """ + headCommitId +
          """; """ + deleteTagOrBranch +
          """
            |
            |commands for sending to remote:
            |  git push origin """ + branch +
          """:refs/for/""" + selectedBranch +
          """; """ + pushTagOrBranch +
          """
            |NOTE: the "send to remote" command pushes the HEAD via Gerrit Code Review, this might not be needed for branches != master""").stripMargin)
    }
    Nil
  }

  def offerAutoFixForReleaseSnapshots(out: PrintStream, mod: PomMod, shellWidth: Int): PomMod = {
    val plugins = mod.listPluginDependecies
    if (mod.hasShopPom) {
      // TODO check if core needs this checks too
      PomChecker.check(plugins)
    }

    case class ReleaseInfo(gav: String, released: Boolean)
    val snaps: Seq[Gav] = mod.listSnapshotsDistinct.map(_.gav()) ++
      plugins.map(_.gav()).filter(_.version.contains("SNAPSHOT"))
    val aetherStateLine = StatusLine(snaps.size, shellWidth)
    val snapState: Seq[ReleaseInfo] = snaps
      .par
      .map(in ⇒ {
        aetherStateLine.start()
        val released = Aether.existsGav(in.groupId, in.artifactId, in.version.replace("-SNAPSHOT", ""))
        aetherStateLine.end()
        ReleaseInfo(in.formatted, released)
      }).seq
    aetherStateLine.finish()

    if (snapState.nonEmpty) {
      out.println("")
      // TODO later autofix
      out.println("Snapshots found for (fix manually):")

      def info(rel: Boolean): String = if (rel) {
        "Release found for "
      } else {
        "No Release for    "
      }

      snapState
        .sortBy(_.toString)
        .map(in ⇒ info(in.released) + in.gav)
        .foreach(println)
      out.println("")

      val again = Term.readFromOneOfYesNo(out, "Try again?")
      if (again == "n") {
        System.exit(1)
      } else {
        offerAutoFixForReleaseSnapshots(out, PomMod(mod.file), shellWidth)
      }
    }
    mod
  }

}