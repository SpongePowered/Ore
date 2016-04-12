package ore.user

import ore.Colors
import ore.Colors.Color
import ore.user.Donor._
import ore.user.UserRoles.UserRole

/**
  * Represents a Sponge donor.
  *
  * @param i            Index
  * @param externalId           ID of role
  * @param title        Title to display
  * @param color        Color to display
  */
class Donor(override val   i: Int,
            override val   externalId: Int,
            override val   title: String,
            override val   color: Color)
            extends        UserRole(i, externalId, TrustLevel, title, color) {

}

object Donor {

  /**
    * The trust level all donors posess.
    */
  val TrustLevel: Int = 1

}
