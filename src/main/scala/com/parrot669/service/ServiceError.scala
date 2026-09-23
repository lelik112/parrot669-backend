package com.parrot669.service

sealed trait ServiceError {
  def message: String
}
object ServiceError {
  final case class Invalid(message: String) extends ServiceError
  final case class NotFound(message: String) extends ServiceError
  final case class Unauthorized(message: String = "authentication required") extends ServiceError
  final case class Conflict(message: String) extends ServiceError
  final case class RateLimited(message: String) extends ServiceError
  final case class Unavailable(message: String) extends ServiceError
}
