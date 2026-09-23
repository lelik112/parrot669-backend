package com.parrot669.messaging

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

final case class MessagingSettings(acceptingNewConversations: Boolean)
final case class ContactOptions(propertyId: String, acceptingNewConversations: Boolean,
    propertyTitle: String, hostProfileId: String, hostDisplayName: String)
final case class BlockRequest(blocked: Boolean)
final case class StartConversationRequest(
    propertyId: String,
    clientMessageId: String,
    body: String,
    from: Option[String] = None,
    to: Option[String] = None
)
final case class SendMessageRequest(
    clientMessageId: String,
    body: String,
    from: Option[String] = None,
    to: Option[String] = None
)
final case class MarkReadRequest(throughSequence: Int)
final case class ReadState(throughSequence: Int)
final case class UnreadCount(conversations: Long, messages: Long)
final case class ConversationStarted(conversationId: String, message: MessageView)
final case class MessageView(
    id: String,
    sequence: Int,
    senderProfileId: String,
    clientMessageId: String,
    body: String,
    from: Option[String],
    to: Option[String],
    createdAt: String
)
final case class ConversationView(
    id: String,
    propertyId: Option[String],
    propertyTitle: String,
    hostProfileId: String,
    guestProfileId: String,
    otherParrotId: String,
    otherDisplayName: String,
    lastSequence: Int,
    readThroughSequence: Int,
    unreadCount: Long,
    lastMessagePreview: String,
    updatedAt: String,
    canReply: Boolean,
    blockedByMe: Boolean,
    blockedByOther: Boolean
)
final case class ConversationPage(items: List[ConversationView], nextCursor: Option[String])
final case class MessagePage(items: List[MessageView], nextAfterSequence: Option[Int])

private[messaging] final case class ValidMessage(
    clientMessageId: UUID, body: String, from: Option[LocalDate], to: Option[LocalDate]
)
private[messaging] final case class InboxCursor(updatedAt: OffsetDateTime, id: UUID)
private[messaging] final case class ConversationRecord(
    id: UUID, propertyId: Option[UUID], propertyTitle: String, hostProfileId: UUID,
    guestProfileId: UUID, lastSequence: Int, hostReadSequence: Int, guestReadSequence: Int
)
private[messaging] final case class MessageRecord(
    id: UUID, sequence: Int, senderProfileId: UUID, clientMessageId: UUID, body: String,
    from: Option[LocalDate], to: Option[LocalDate], createdAt: OffsetDateTime
) {
  def view: MessageView = MessageView(id.toString, sequence, senderProfileId.toString,
    clientMessageId.toString, body, from.map(_.toString), to.map(_.toString), createdAt.toString)
}
private[messaging] final case class InboxRecord(
    id: UUID, propertyId: Option[UUID], propertyTitle: String, hostProfileId: UUID,
    guestProfileId: UUID, otherParrotId: String, otherDisplayName: String,
    lastSequence: Int, readThroughSequence: Int, unreadCount: Long,
    lastMessagePreview: String, updatedAt: OffsetDateTime, blockedByMe: Boolean, blockedByOther: Boolean
) {
  def view: ConversationView = ConversationView(id.toString, propertyId.map(_.toString),
    propertyTitle, hostProfileId.toString, guestProfileId.toString, otherParrotId,
    otherDisplayName, lastSequence, readThroughSequence, unreadCount,
    lastMessagePreview, updatedAt.toString, canReply = propertyId.isDefined && !blockedByMe && !blockedByOther,
    blockedByMe, blockedByOther)
}
