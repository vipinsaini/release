package release

import java.io.File

import org.junit._
import org.junit.rules.TemporaryFolder
import org.scalatest.junit.AssertionsForJUnit
import redis.clients.jedis.Jedis

class ReleaseConfigTest extends AssertionsForJUnit {

  val _temporarayFolder = new TemporaryFolder()

  @Rule def temp = _temporarayFolder

  var file: File = null

  @Before
  def tempConfig(): Unit = {
    file = temp.newFile()
    file.deleteOnExit()
  }

  @After
  def cleanup(): Unit = {
    file.delete()
  }

  @Test
  def testJedis(): Unit = {
    try {
      val jedis = new Jedis("localhost", 12345, 20, 20)
      val pass = "none"
      // jedis.configSet("requirepass", pass)
      jedis.auth(pass)

      def push(key: String, value: String): Unit = {
        jedis.rpush(key, value)
        jedis.expire(key, 2)
      }

      push("foor", "hallo")
      val long = jedis.llen("foor")
      val value = jedis.lrange("foor", 0, long)

      println(value)
    } catch {
      case e: Throwable ⇒ println("E: " + e.getMessage)
    }
  }
}