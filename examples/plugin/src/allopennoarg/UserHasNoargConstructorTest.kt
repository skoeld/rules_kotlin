package plugin.allopennoarg

import org.junit.Test
import java.lang.Exception

class UserHasNoargConstructorTest {
  @Test
  fun userShouldHaveNoargConstructor() {
    if (User::class.java.constructors.none { it.parameters.isEmpty() }) {
      throw Exception("Expected an empty constructor to exist")
    }
  }
}
