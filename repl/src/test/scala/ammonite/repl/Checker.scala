package ammonite.repl

import ammonite.repl.frontend._
import ammonite.repl.interp.Interpreter
import utest._

/**
 * A test REPL which does not read from stdin or stdout files, but instead lets
 * you feed in lines or sessions programmatically and have it execute them.
 */
class Checker {
  def predef = ""
  var allOutput = ""


  val tempDir = java.nio.file.Files.createTempDirectory("ammonite-tester").toFile

  val interp = new Interpreter(
    Ref[String](""),
    Ref(null),
    pprint.Config.Defaults.PPrintConfig.copy(height = 15),
    Ref(ColorSet.BlackWhite),
    stdout = allOutput += _,
    storage = Ref(Storage(tempDir)),
    predef = predef
  )

  def session(sess: String): Unit = {
    // Remove the margin from the block and break
    // it into blank-line-delimited steps
    val margin = sess.lines.filter(_.trim != "").map(_.takeWhile(_ == ' ').length).min
    val steps = sess.replace("\n" + margin, "\n").split("\n\n")

    for(step <- steps){
      // Break the step into the command lines, starting with @,
      // and the result lines
      val (cmdLines, resultLines) =
        step.lines
            .map(_.drop(margin))
            .partition(_.startsWith("@"))

      val commandText = cmdLines.map(_.stripPrefix("@ ")).toVector

      // Make sure all non-empty, non-complete command-line-fragments
      // are considered incomplete during the parse
      for (incomplete <- commandText.inits.toSeq.drop(1).dropRight(1)){
        assert(Parsers.split(incomplete.mkString("\n")) == None)
      }

      // Finally, actually run the complete command text through the
      // interpreter and make sure the output is what we expect
      val expected = resultLines.mkString("\n").trim
      allOutput += commandText.map("\n@ " + _).mkString("\n")

      val (processed, printed) = run(commandText.mkString("\n"))
      interp.handleOutput(processed)
      if (expected.startsWith("error: ")){
        printed match{
          case Res.Success(v) => assert({v; allOutput; false})
          case Res.Failure(failureMsg) =>
            val expectedStripped =
              expected.stripPrefix("error: ").replaceAll(" *\n", "\n")
            val expectedRegex = createRegex(expectedStripped).r //using scala Regex here
            val failureStripped = failureMsg.replaceAll("\u001B\\[[;\\d]*m", "").replaceAll(" *\n", "\n")
            failLoudly(assert(!expectedRegex.findFirstIn(failureStripped).isEmpty))
        }
      }else{
        if (expected != ""){
          val regex = createRegex(expected)
          printed match {
            case Res.Success(str) => failLoudly(assert(str.matches(regex)))
            case _ => assert({printed; regex; false})
          }
        }
      }
    }
  }

  /* This method creates a regex from the expected string escaping all specials, so you don't have to bother with
   * escaping the in tests, if they are not needed. Special meanings can be activated by inserting a backslash
   * before the special character. This is essentially vim's nomagic mode.
   */
  def createRegex(expected: String) = {
    val specialChars=".|+*?[](){}^$" //these characters need to be escaped to use them as regex specials.
    //idk why do i need 4 backsalshes in the replacement
    val escape = specialChars.map{ c => (s"\\$c", s"\\\\$c") } //first part is handled as a regex, so we need the escape to match the literal character.
    val escapedExpected = escape.foldLeft(expected){ case (exp, (pattern, replacement)) => exp.replaceAll(pattern, replacement) } // We insert a backslash so special chars are handled as literals
    val unescape = specialChars.map{ c => (s"\\\\\\\\\\$c", c.toString) } // special chars that had a backslash before them now have two.
    unescape.foldLeft(escapedExpected){ case (exp, (pattern, replacement)) => exp.replaceAll(pattern, replacement) } // we replace double backslashed stuff with regex specials
  }

  def run(input: String) = {
//    println("RUNNING")
//    println(input)
//    print(".")
    val msg = collection.mutable.Buffer.empty[String]
    val processed = interp.processLine(input, Parsers.split(input).get.get.value, _.foreach(msg.append(_)))
    val printed = processed.map(_ => msg.mkString)

    interp.handleOutput(processed)
    (processed, printed)
  }


  def fail(input: String,
           failureCheck: String => Boolean = _ => true) = {
    val (processed, printed) = run(input)

    printed match{
      case Res.Success(v) => assert({v; allOutput; false})
      case Res.Failure(s) =>

        failLoudly(assert(failureCheck(s)))
    }
  }

  def result(input: String, expected: Res[Evaluated]) = {
    val (processed, printed) = run(input)
    assert(processed == expected)
  }
  def failLoudly[T](t: => T) =
    try t
    catch{ case e: utest.AssertionError =>
      println("FAILURE TRACE\n" + allOutput)
      throw e
    }

}