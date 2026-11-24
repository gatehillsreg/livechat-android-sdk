# Changelog

## 1.0.0 — 2026-11-24

First release.

- The livechat.ae chat in a `WebView`, with the same settings, forms, departments, languages and knowledge base as the website chat
- `LiveChatAE.configure`, `present`, `dismiss`, `setVisitor`, `setCustomFields`, `identify`, `setDepartment`, `reset`
- Events through `LiveChatAEListener` and lambdas, on the main thread; `unreadCount`, `isPresented`, `agentsOnline`, `lastError`
- Ready-made chat button with the unread badge: `LiveChatAEButton` (Views, XML and Compose through `AndroidView`)
- Links and attachments in a Custom Tab, in the browser or handled by the app; file picker for attachments
- Push notifications through the `chat.agent_message` webhook: `setPushToken` (FCM), `handleNotification`
- JitPack
