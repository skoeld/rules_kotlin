package plugin.allopennoarg

import java.util.UUID

@OpenForTesting
@NoArgConstructor
data class User(
  val userId: UUID,
  val emails: String
)
