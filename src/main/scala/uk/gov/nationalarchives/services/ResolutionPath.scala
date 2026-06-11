package uk.gov.nationalarchives.services

sealed trait ResolutionPath {
  val id: String
}

object ResolutionPath {
  case object UserFixable extends ResolutionPath {
    val id: String = "UserFixable"
  }
  case object TNASupport extends ResolutionPath {
    val id: String = "TNASupport"
  }
  case object UserFixableAndTNASupport extends ResolutionPath {
    val id: String = "UserFixableAndTNASupport"
  }
}
