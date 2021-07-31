package plugin.noarg

import java.util.UUID

@NoArgConstructor
data class User(
  val userId: UUID,
  val emails: String
)
