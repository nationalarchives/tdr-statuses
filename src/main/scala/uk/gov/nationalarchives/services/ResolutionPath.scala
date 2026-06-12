package uk.gov.nationalarchives.services

sealed trait ResolutionPath {
  val id: String
}

object ResolutionPath {
  case object UserFixable extends ResolutionPath {
    val id: String = "User Fixable"
  }
  case object TNASupport extends ResolutionPath {
    val id: String = "TNA Support"
  }
  case object UserFixableAndTNASupport extends ResolutionPath {
    val id: String = "User Fixable And TNA Support"
  }
}
