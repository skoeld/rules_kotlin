package plugin.allopen

import java.util.UUID

@OpenForTesting
data class User(
  val userId: UUID,
  val emails: String
)
