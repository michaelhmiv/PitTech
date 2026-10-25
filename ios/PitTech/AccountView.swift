import SwiftUI
import AuthenticationServices

/// PL-14 (#172): a Form section header that carries the section's status
/// under its title (DD-2) — the same five Account sections, with the same
/// status words, as Android's Account sheet.
private struct AccountSectionHeader: View {
    let title: String
    let status: String
    let statusId: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            Text(status).font(.footnote).textCase(nil).accessibilityIdentifier(statusId)
        }
    }
}

struct NativeAccountView: View {
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(NativePrivateSyncCoordinator.self) private var sync
    @Environment(NativeMcpConnections.self) private var mcp
    @Environment(NativeMcpDeviceActions.self) private var deviceActions
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @State private var email = ""
    @State private var code = ""
    @State private var confirmingSignOut = false
    @State private var reviewingDeletion = false
    @State private var reviewingSync = false
    @State private var reviewingJournal = false
    @State private var creatingConnection = false
    private enum Field: Hashable { case email, code }
    @FocusState private var focused: Field?
    /// Slice 7 (PL-10): Account's sync status row copy, derived from the
    /// coordinator's existing presentation — presentation-layer only.
    private var syncRowCopy: SyncStatusRowCopy {
        SyncStatusCopy.row(status: sync.presentation.status, detail: sync.presentation.detail, lastSyncedAt: sync.presentation.lastSyncedAt, conflictCount: sync.presentation.conflicts.count, signedIn: account.signedIn, enabled: sync.enabled)
    }
    private var syncDetailInRow: Bool {
        sync.presentation.conflicts.isEmpty && sync.enabled && account.signedIn &&
            (sync.presentation.status == "Waiting for connection" || sync.presentation.status == "Retry needed" || sync.presentation.lastSyncedAt > 0)
    }
    /// PL-14: the Session section's status, carried in its header (DD-2).
    private var sessionStatus: String {
        if account.deletionRecord != nil { return "Deletion in progress" }
        if account.signedIn { return "Signed in" }
        if !account.enabled { return "Not available on this build" }
        return "Signed out"
    }
    var body: some View {
        Form {
            Section { Text("Your cookbook works without an account. Signing in doesn't publish or upload your local records.") }
            // PL-14 (#172): Account is segmented into five sections, each
            // header carrying its status (DD-2) — Session · Sync · Connect
            // your AI · Device actions · Danger zone, mirroring Android.
            if !account.enabled {
                Section {
                    Text("Accounts arrive with community recipes and private sync.").accessibilityIdentifier("account-unavailable")
                } header: {
                    AccountSectionHeader(title: "Session", status: sessionStatus, statusId: "account-panel-session-status")
                }
            } else {
                Section {
                    if let deletion = account.deletionRecord {
                        Text(deletion.receipt?.description ?? "The deletion response was not confirmed. Recover the request before retrying.").accessibilityIdentifier("account-deletion-status")
                        Text("Your local journal stays available on this device. Deletion does not stop your local cooking.")
                        if deletion.cleanupUserId != nil { Button("Retry device account cleanup") { account.retryDeviceCleanup() }.disabled(account.busy) }
                        if deletion.receipt?.state == "complete" {
                            Button("Close completed request") { account.dismissCompletedDeletion() }.disabled(account.busy)
                        } else {
                            if deletion.receipt != nil { Button("Check deletion progress") { Task { await account.checkDeletion() } }.disabled(account.busy).accessibilityIdentifier("account-deletion-check") }
                            Button("Recover deletion request") { Task { await account.recoverDeletion() } }.disabled(account.busy).accessibilityIdentifier("account-deletion-recover")
                            if deletion.receipt == nil && account.signedIn { Button("Review and retry the same request") { reviewingDeletion = true }.disabled(account.busy).accessibilityIdentifier("account-deletion-retry") }
                        }
                    } else if account.signedIn {
                        Text("Signed in").accessibilityIdentifier("account-signed-in")
                        if let email = account.accountEmail {
                            Text("Email sign-in: \(email)").privacySensitive().accessibilityIdentifier("account-auth-email")
                        }
                        if account.appleLinked {
                            Text("Apple sign-in is linked.").accessibilityIdentifier("account-auth-apple")
                        } else if account.appleSignInAvailable {
                            Button("Add Apple sign-in") { Task { await account.attachApple() } }.disabled(account.busy).accessibilityIdentifier("account-apple-attach")
                        }
                        Button("Check connection") { Task { await account.checkSession() } }.disabled(account.busy)
                        Button("Sign out", role: .destructive) { confirmingSignOut = true }.disabled(account.busy)
                    } else {
                        Text("You're signed out. Sign in to use private sync and AI connections.").accessibilityIdentifier("account-signin-note")
                    }
                } header: {
                    AccountSectionHeader(title: "Session", status: sessionStatus, statusId: "account-panel-session-status")
                }
                if let deletion = account.deletionRecord, deletion.receipt == nil && !account.signedIn {
                    Section { Text("Verify the original account email to retry this device’s saved request. Another account cannot take over the request.") }
                    signInContent
                } else if !account.signedIn && account.deletionRecord == nil { signInContent }
            }
            Section {
                Text("Sync keeps an encrypted backup of this journal for signed-in cooks. It never publishes anything.").font(.footnote)
                // PL-14 (#172): signing in surfaces the first sync as its
                // own card, so turning private sync on is discoverable
                // instead of depending on knowing that "Sync now" below is
                // also the on switch. The card is the same one action —
                // the coordinator's explicit sync.
                if account.signedIn && sync.enabled && sync.presentation.lastSyncedAt <= 0 && sync.presentation.conflicts.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Turn on private sync").font(.headline)
                        Text("Make the first encrypted backup of this journal now. After that, you can keep it in sync automatically while the app is open.").font(.footnote)
                        Button("Turn on private sync") { Task { await sync.syncNow() } }.disabled(sync.presentation.busy).accessibilityIdentifier("sync-turn-on")
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(PitTechStyle.accentFill, in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("sync-turn-on-card")
                }
                // Slice 7 (PL-10, DD-8): the sync status row always carries state —
                // what happened, what is still safe, and the recovery action —
                // instead of the coordinator's bare status string. First sync is
                // an Account action: "Sync now" below is the same button in every
                // state, and the row says what it will do before the first backup
                // exists.
                VStack(alignment: .leading, spacing: 2) {
                    Text(syncRowCopy.headline).font(.subheadline.weight(.semibold)).accessibilityIdentifier("sync-status")
                    Text(syncRowCopy.body).font(.footnote).accessibilityIdentifier("sync-status-detail")
                }
                // Without .contain, this container's identifier replaces the
                // children's in the AX tree (the same defect #191 fixed on the
                // empty Meals card): no element answered to "sync-status" or
                // "sync-status-detail", and flows-a's conflict test failed at
                // "sync-status unreachable" with the row on screen.
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("sync-status-row")
                // The detail is already the row's body in the states that use it;
                // only repeat it as the message line where the row's body is the
                // fixed copy.
                if !syncDetailInRow, let detail = sync.presentation.detail { Text(detail).font(.footnote).accessibilityIdentifier("sync-message") }
                if sync.presentation.lastSyncedAt > 0 && account.signedIn && sync.enabled {
                    Toggle("Keep this library in sync while the app is open", isOn: Binding(get: { sync.presentation.automatic }, set: { sync.setAutomatic($0) })).disabled(sync.presentation.busy).accessibilityIdentifier("sync-automatic")
                    Text("Checks when you return to the app and after saved changes. Offline changes wait for a connection.").font(.footnote)
                }
                Button("Sync now") { Task { await sync.syncNow() } }.disabled(sync.presentation.busy || !account.signedIn || !sync.enabled).accessibilityIdentifier("sync-now")
                if !sync.presentation.conflicts.isEmpty { Button("Review conflicts (\(sync.presentation.conflicts.count))") { reviewingSync = true }.disabled(sync.presentation.busy).accessibilityIdentifier("sync-conflicts") }
                if sync.presentation.busy { ProgressView("Syncing…") }
            } header: {
                AccountSectionHeader(title: "Sync", status: syncRowCopy.headline, statusId: "account-panel-sync-status")
            }
            .onAppear { sync.refresh() }
            .onChange(of: account.signedIn) { _, _ in sync.refresh() }
            if account.enabled && account.signedIn && account.deletionRecord == nil {
                // Slice 8's sections, unchanged inside; their headers now
                // carry the section status like every other section.
                McpConnectionsSection(mcp: mcp, creating: $creatingConnection)
                McpDeviceActionsSection(device: deviceActions)
            } else if account.enabled && account.deletionRecord == nil {
                Section {
                    Text("Sign in above to connect an AI assistant like ChatGPT or Muse to your own meals, recipes and logs.").font(.footnote).accessibilityIdentifier("account-ai-signin-note")
                } header: {
                    AccountSectionHeader(title: "Connect your AI", status: "Sign in to connect", statusId: "account-panel-ai-status")
                }
                Section {
                    Text("Sign in above to let an assistant ask this phone to do small things in the app — never to control a grill.").font(.footnote).accessibilityIdentifier("account-device-signin-note")
                } header: {
                    AccountSectionHeader(title: "Device actions", status: "Sign in to connect", statusId: "account-panel-device-status")
                }
            }
            Section {
                if account.signedIn && account.deletionRecord == nil {
                    Text("Delete account and server data").font(.headline)
                    Text("Deletes your account identity, profile, sessions, likes, private server sync and original public publications. Your local journal stays on this device unless you remove it below.").font(.footnote)
                    Button("Delete account and server data", role: .destructive) { reviewingDeletion = true }.disabled(account.busy).accessibilityIdentifier("account-delete")
                }
                Text("Remove local journal").font(.headline)
                Text("Removes this device's cooks, recipes, photos, notes, drafts, reminders and timers in the journal. This does not delete the server account; credentials, deletion records and recovery, and community block and report device state stay untouched.")
                Button("Remove local journal", role: .destructive) { reviewingJournal = true }.disabled(account.busy).accessibilityIdentifier("local-journal-remove")
            } header: {
                AccountSectionHeader(title: "Danger zone", status: "Delete account · Remove local journal", statusId: "account-panel-danger-status")
            }
            if account.busy { ProgressView("Connecting…") }
            if let message = account.message { Section { Text(message).accessibilityIdentifier("account-message") } }
        }
        .navigationTitle("Account").navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Hide keyboard") { focused = nil } }
        }
        .scrollDismissesKeyboard(.interactively)
        .sheet(isPresented: $reviewingDeletion) { NavigationStack { NativeDeletionReviewView { reviewingDeletion = false; Task { await account.requestDeletion() } } } }
        .sheet(isPresented: $creatingConnection) { NavigationStack { McpCreateView(mcp: mcp) { creatingConnection = false } } }
        .sheet(isPresented: $reviewingSync) { NavigationStack { NativeSyncConflictReviewView() } }
        .sheet(isPresented: $reviewingJournal) { NavigationStack { NativeLocalJournalReviewView { reviewingJournal = false; account.removeLocalJournal() } } }
        .confirmationDialog("Sign out on this device?", isPresented: $confirmingSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { Task { await account.signOut() } }.accessibilityIdentifier("account-confirm-signout")
        } message: { Text("Your local cooks, recipes and photos stay on this device. Signing out doesn't delete your account.") }
    }
    @ViewBuilder private var signInContent: some View {
        Section("Sign in by email") {
            TextField("Email address", text: $email).textContentType(.emailAddress).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled().focused($focused, equals: .email).privacySensitive().accessibilityIdentifier("account-email")
            Button("Send sign-in email") { focused = nil; Task { await account.start(email) } }.disabled(account.busy || email.isEmpty).accessibilityIdentifier("account-start")
        }
        if account.appleSignInAvailable {
            Section("Sign in with Apple") {
                AppleSignInButton(style: colorScheme == .dark ? .white : .black, enabled: !account.busy) { Task { await account.signInWithApple() } }
                    .frame(height: 44)
            }
        }
        if let address = account.challengeEmail {
            Section("Check your email") {
                Text("Enter the six-digit code sent to \(address). You can open the email on another device.").privacySensitive()
                TextField("Six-digit code", text: $code).keyboardType(.numberPad).textContentType(.oneTimeCode).focused($focused, equals: .code).privacySensitive().accessibilityIdentifier("account-code")
                Button(account.interruptedVerification ? "Retry sign-in" : account.pendingLink != nil ? "Confirm email link" : "Verify code") { focused = nil; Task { await account.verify(code); code = "" } }.disabled(account.busy).accessibilityIdentifier("account-verify")
                TimelineView(.periodic(from: .now, by: 1)) { _ in Button("Send a new code") { Task { await account.start(address, resend: true) } }.disabled(account.busy || !account.canResend).accessibilityIdentifier("account-resend") }
                Text("Codes expire after 15 minutes. Wait at least a minute before requesting another.").font(.footnote)
            }
        }
    }
}

private struct McpConnectionsSection: View {
    let mcp: NativeMcpConnections
    @Binding var creating: Bool
    @Environment(ProSubscriptionCoordinator.self) private var pro
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""
    @State private var confirmingRevoke: String?
    @State private var confirmingRevokeAll = false
    @State private var tokenCopied = false
    /// Slice 8 (PL-14): the gate reads the existing subscription state.
    private var gate: McpProGate { mcpProGate(for: pro.entitlement) }
    /// PL-14 (#172): the section header's status (DD-2), mirroring Android.
    private var aiStatus: String {
        if !mcp.available { return "Not available yet" }
        switch gate {
        case .checking: return "Checking Pro status"
        case .needsPro: return "Needs PitTech Pro"
        case .entitled:
            let active = mcp.grants.filter(\.active).count
            return active > 0 ? "\(active) connection\(active == 1 ? "" : "s")" : "No connections"
        }
    }
    var body: some View {
        Section {
            Text("Let an AI assistant like ChatGPT or Muse help with your own meals, recipes and logs. Connections use your PitTech Pro and can be disconnected here any time. An assistant can never publish, control a grill, or change your account or billing.").font(.footnote)
            if !mcp.available {
                Text("AI connections aren't available yet.").accessibilityIdentifier("mcp-unavailable")
            } else {
                ForEach(mcp.grants) { grant in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(grant.nickname).font(.headline)
                        Text("\(grant.clientId != nil ? "Connected app" : "Personal access token") · \(grant.status(now: Int64(Date().timeIntervalSince1970 * 1000)))").font(.footnote)
                        Text(grant.scopes.map(McpScopeCatalog.label).joined(separator: ", ")).font(.footnote)
                        if let used = grant.lastUsedAt { Text("Last used \(Date(timeIntervalSince1970: Double(used) / 1000), format: .dateTime)").font(.footnote) }
                        if grant.active { Button("Disconnect", role: .destructive) { confirmingRevoke = grant.id }.disabled(mcp.busy).accessibilityIdentifier("mcp-revoke-\(grant.id)") }
                    }.accessibilityIdentifier("mcp-grant-\(grant.id)")
                }
                if mcp.grants.allSatisfy({ !$0.active }) { Text("No AI connections yet.").font(.footnote) }
                if mcp.grants.filter(\.active).count > 1 { Button("Disconnect all", role: .destructive) { confirmingRevokeAll = true }.disabled(mcp.busy).accessibilityIdentifier("mcp-revoke-all") }
                switch gate {
                case .checking:
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Checking PitTech Pro status…").font(.headline)
                        Text("Creating or approving a connection needs your verified Pro status first. Your existing connections are unchanged.").font(.footnote)
                        Button("Check again") { Task { await pro.reconcile() } }.accessibilityIdentifier("mcp-pro-check")
                    }.accessibilityIdentifier("mcp-pro-checking")
                case .needsPro:
                    VStack(alignment: .leading, spacing: 4) {
                        Text("AI connections need PitTech Pro").font(.headline)
                        Text("Creating or approving a connection needs an active PitTech Pro subscription — without it, the assistant can't use the connection, so PitTech doesn't create one that would only fail later. Your existing connections stay listed above and can be disconnected any time.").font(.footnote)
                        Button("See PitTech Pro") { pro.openPro(.more); dismiss() }.accessibilityIdentifier("mcp-pro-see")
                    }.accessibilityIdentifier("mcp-pro-gate")
                case .entitled:
                    Button("New connection") { creating = true }.disabled(mcp.busy).accessibilityIdentifier("mcp-new")
                    Text("Approve a connection").font(.headline)
                    Text("If an assistant shows you a code in your browser, enter it here. PitTech shows you exactly what the code connects before anything is approved.").font(.footnote)
                    TextField("Connection code", text: $code).textInputAutocapitalization(.characters).autocorrectionDisabled().accessibilityIdentifier("mcp-approve-code")
                        .onChange(of: code) { _, entered in
                            if let preview = mcp.approvalPreview, preview.code != entered.trimmingCharacters(in: .whitespaces).uppercased() { mcp.dismissApprovalPreview() }
                        }
                    if let preview = mcp.approvalPreview {
                        // Slice 8 (PL-16 / DD-9): the confirmation step.
                        // Approve does not exist until the client and its
                        // full scope list have been shown for this code.
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Connect \(preview.clientName)?").font(.headline)
                            Text("\(preview.clientName) will be able to:").font(.footnote)
                            ForEach(preview.scopes, id: \.self) { Text("• \(McpScopeCatalog.label($0))").font(.footnote) }
                            Text("PitTech Pro: active on this account.").font(.footnote)
                            Text("This code expires \(Date(timeIntervalSince1970: Double(preview.expiresAt) / 1000), format: .dateTime). Nothing is connected until you approve.").font(.footnote)
                            Button("Approve") { let reviewed = preview.code; code = ""; Task { await mcp.approve(userCode: reviewed) } }.disabled(mcp.busy).accessibilityIdentifier("mcp-approve")
                            Button("Not now") { mcp.dismissApprovalPreview() }.disabled(mcp.busy).accessibilityIdentifier("mcp-approve-cancel")
                        }.accessibilityIdentifier("mcp-approval-preview")
                    } else {
                        Button("Review connection") { Task { await mcp.reviewCode(code) } }.disabled(mcp.busy || code.isEmpty).accessibilityIdentifier("mcp-review-code")
                    }
                }
                if mcp.busy { ProgressView("Working…") }
                if let message = mcp.message { Text(message).accessibilityIdentifier("mcp-message") }
            }
        } header: {
            AccountSectionHeader(title: "Connect your AI", status: aiStatus, statusId: "account-panel-ai-status")
        }
        .onAppear { Task { await mcp.refresh() } }
        .onChange(of: mcp.createdToken) { _, _ in tokenCopied = false }
        // Slice 8 (PL-16 / DD-9): the show-once token is a hard-stop sheet,
        // presented the moment creation completes — not a block that can
        // materialise off-screen in the account scroll. A sheet, not an
        // alert: every alert button dismisses, which would burn the token
        // when Copy is tapped. Here Copy confirms itself ("Copied") in
        // place, swipe-dismiss is disabled, and Done is the only exit.
        .sheet(isPresented: Binding(get: { mcp.createdToken != nil }, set: { if !$0 { mcp.dismissCreatedToken() } })) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 12) {
                    Text("For \(mcp.createdNickname ?? "the new connection")").font(.headline)
                    Text("Copy it into the assistant now. It is shown only once and is not saved on this device.").font(.footnote)
                    Text(mcp.createdToken ?? "").textSelection(.enabled).accessibilityIdentifier("mcp-token-value")
                    Button(tokenCopied ? "Copied" : "Copy token") {
                        if let token = mcp.createdToken { UIPasteboard.general.string = token }
                        tokenCopied = true
                    }.accessibilityIdentifier("mcp-copy-token")
                    Button("Done") { mcp.dismissCreatedToken() }.accessibilityIdentifier("mcp-token-done")
                    Spacer()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
                .accessibilityIdentifier("mcp-created-token")
                .navigationTitle("Your connection token")
                .navigationBarTitleDisplayMode(.inline)
            }
            .interactiveDismissDisabled()
            .presentationDetents([.medium])
        }
        .confirmationDialog("Disconnect this connection?", isPresented: Binding(get: { confirmingRevoke != nil }, set: { if !$0 { confirmingRevoke = nil } }), titleVisibility: .visible) {
            Button("Disconnect", role: .destructive) { if let id = confirmingRevoke { confirmingRevoke = nil; Task { await mcp.revoke(id) } } }.accessibilityIdentifier("mcp-confirm-revoke")
        } message: { Text("The assistant using it loses access immediately. Your library is unchanged.") }
        .confirmationDialog("Disconnect all connections?", isPresented: $confirmingRevokeAll, titleVisibility: .visible) {
            Button("Disconnect all", role: .destructive) { confirmingRevokeAll = false; Task { await mcp.revokeAll() } }.accessibilityIdentifier("mcp-confirm-revoke-all")
        } message: { Text("Every assistant using a connection loses access immediately. Your library is unchanged.") }
    }
}
private struct McpCreateView: View {
    let mcp: NativeMcpConnections
    let done: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var nickname = "ChatGPT"
    @State private var selected: Set<String> = McpScopeCatalog.defaultSelected
    @State private var days = 90
    @State private var attempted = false
    var body: some View {
        Form {
            Section { TextField("Name this connection", text: $nickname).accessibilityIdentifier("mcp-nickname") }
            Section("What it can do") {
                ForEach(McpScopeCatalog.all) { entry in
                    Toggle(isOn: Binding(get: { selected.contains(entry.id) }, set: { selected = $0 ? selected.union([entry.id]) : selected.subtracting([entry.id]) })) {
                        VStack(alignment: .leading) { Text(entry.label); Text(entry.detail).font(.footnote) }
                    }.accessibilityIdentifier("mcp-scope-\(entry.id)")
                }
            }
            Section("Disconnect automatically after") {
                Picker("Expiry", selection: $days) { Text("30 days").tag(30); Text("90 days").tag(90); Text("365 days").tag(365) }.pickerStyle(.segmented).accessibilityIdentifier("mcp-days")
                Text("You can disconnect sooner any time. Nothing here can publish, control a grill, or change billing.").font(.footnote)
            }
            Section {
                // Slice 8 (PL-16): the form stays open until the create
                // call resolves — success hands off to the hard-stop
                // token sheet; failure keeps the filled form and says
                // what happened, here.
                Button("Create connection") {
                    let name = nickname, scopes = selected, expiry = days
                    attempted = true
                    Task {
                        await mcp.create(nickname: name, scopes: scopes, expiresInDays: expiry)
                        if mcp.createdToken != nil { done() }
                    }
                }.disabled(nickname.isEmpty || selected.isEmpty || mcp.busy).accessibilityIdentifier("mcp-create-submit")
                if mcp.busy && attempted { ProgressView("Creating…") }
                if attempted && !mcp.busy && mcp.createdToken == nil, let message = mcp.message {
                    Text(message).font(.footnote).accessibilityIdentifier("mcp-create-error")
                }
            }
        }.navigationTitle("New AI connection").navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
    }
}

/// Apple's provided Sign in with Apple button. The tap runs the coordinator's
/// proof-bound flow (challenge first, sheet second), so the sheet itself is
/// presented programmatically rather than through SignInWithAppleButton.
private struct AppleSignInButton: UIViewRepresentable {
    let style: ASAuthorizationAppleIDButton.Style
    let enabled: Bool
    let action: () -> Void
    func makeUIView(context: Context) -> ASAuthorizationAppleIDButton {
        let button = ASAuthorizationAppleIDButton(type: .continue, style: style)
        button.accessibilityIdentifier = "account-apple-signin"
        button.addTarget(context.coordinator, action: #selector(Coordinator.tapped), for: .touchUpInside)
        return button
    }
    func updateUIView(_ view: ASAuthorizationAppleIDButton, context: Context) {
        view.isEnabled = enabled
    }
    func makeCoordinator() -> Coordinator { Coordinator(action: action) }
    final class Coordinator: NSObject {
        let action: () -> Void
        init(action: @escaping () -> Void) { self.action = action }
        @objc func tapped() { action() }
    }
}
private struct NativeDeletionReviewView: View {
    let confirm: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var acknowledged = false
    @State private var typed = ""
    @FocusState private var typing: Bool
    var body: some View {
        Form {
            Section("Server account and originals") {
                Text("Delete your account identity, profile, sessions, likes, private server sync, original public publications and original media.")
                Text("Other cooks keep independently owned copies and variations they already saved. Those copies show a removed source without your identity.")
                Text("This request signs you out immediately. Cleanup can take time; keep this device’s recovery record until it completes.")
            }
            Section("Local journal") { Text("Your journal stays on this device, including an active local cook. Keeping it does not retain your server account data. Local data removal is a separate choice.") }
            Section("Subscriptions") {
                Text("Apple manages subscriptions separately. You can delete your account immediately without changing billing first.")
                Link("Manage Apple subscriptions", destination: URL(string: "https://apps.apple.com/account/subscriptions")!)
            }
            Section {
                Toggle("I understand what is deleted and what other cooks keep", isOn: $acknowledged).accessibilityIdentifier("account-deletion-acknowledge")
                TextField("Type DELETE", text: $typed).textInputAutocapitalization(.characters).autocorrectionDisabled().focused($typing).accessibilityIdentifier("account-deletion-confirmation")
                Button("Delete account and server data", role: .destructive) { typing = false; confirm() }.disabled(!acknowledged || typed != "DELETE").accessibilityIdentifier("account-deletion-submit")
            }
        }.navigationTitle("Delete account").navigationBarTitleDisplayMode(.inline).scrollDismissesKeyboard(.interactively)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Hide keyboard") { typing = false } } }
    }
}
/// PL-16 (#172): the conflict review shows both versions as readable
/// content — fields, not one-line summaries — under "Yours" and "Other
/// device", and prints the consequence on each choice itself: it applies on
/// this device right away and uploads on the next sync. Where a record kind
/// cannot keep both versions, the sheet says so for that item instead of
/// the button silently not being there.
struct NativeSyncConflictReviewView: View {
    @Environment(NativePrivateSyncCoordinator.self) private var sync
    @Environment(NativeMcpConnections.self) private var mcp
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        Form {
            Section {
                Text("Two devices changed the same entry. Your journal keeps the current version until you choose.")
                // Slice 7 (PL-10): the consequence is computed from the actual
                // pending set, never a generic warning.
                Text(SyncStatusCopy.conflictConsequence(sync.presentation.conflicts.count)).font(.footnote).accessibilityIdentifier("sync-conflict-consequence")
            }
            ForEach(sync.presentation.conflicts) { conflict in
                Section {
                    NativeSyncConflictSide(title: "Yours", fields: conflict.mineFields).accessibilityIdentifier("sync-conflict-mine-\(conflict.id)")
                    NativeSyncConflictSide(title: "Other device", fields: conflict.theirsFields).accessibilityIdentifier("sync-conflict-theirs-\(conflict.id)")
                    // Each choice carries its own consequence, printed before
                    // the tap: immediate on this device, uploaded next sync.
                    NativeSyncConflictChoice(choice: "mine", label: "Keep mine", conflictId: conflict.id, busy: sync.presentation.busy) { await sync.resolve(conflict.id, choice: "mine") }
                    NativeSyncConflictChoice(choice: "theirs", label: "Use theirs", conflictId: conflict.id, busy: sync.presentation.busy) { await sync.resolve(conflict.id, choice: "theirs") }
                    if conflict.canKeepBoth {
                        NativeSyncConflictChoice(choice: "both", label: "Keep both", conflictId: conflict.id, busy: sync.presentation.busy) { await sync.resolve(conflict.id, choice: "both") }
                    } else {
                        Text(SyncStatusCopy.conflictKeepBothUnavailable(conflict.recordType)).font(.footnote).accessibilityIdentifier("sync-keep-both-unavailable-\(conflict.id)")
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(conflict.reason)
                        Text(conflict.recordLabel).font(.caption)
                    }
                }
            }
        }.navigationTitle("Review synced edits").navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.accessibilityIdentifier("sync-review-done") } }
    }
}

/// One side of the PL-16 comparison: the version's actual content fields.
private struct NativeSyncConflictSide: View {
    let title: String
    let fields: [NativeConflictPreviewField]
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.headline)
            ForEach(Array(fields.enumerated()), id: \.offset) { _, field in
                VStack(alignment: .leading, spacing: 1) {
                    Text(field.label).font(.caption).foregroundStyle(.secondary)
                    Text(field.value)
                }
            }
        }
    }
}

/// A resolution button with its PL-16 consequence printed directly under it.
private struct NativeSyncConflictChoice: View {
    let choice: String
    let label: String
    let conflictId: String
    let busy: Bool
    let resolve: () async -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Button(label) { Task { await resolve() } }.disabled(busy).accessibilityIdentifier("sync-keep-\(choice)-\(conflictId)")
            Text(SyncStatusCopy.conflictChoiceNote(choice)).font(.footnote).accessibilityIdentifier("sync-conflict-note-\(choice)-\(conflictId)")
        }
    }
}
private struct NativeLocalJournalReviewView: View {
    let confirm: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var acknowledged = false
    @State private var typed = ""
    @FocusState private var typing: Bool
    var body: some View {
        Form {
            Section("This device's journal") {
                Text("Removes this device's cooks, recipes, photos, notes, drafts, reminders and timers in the journal. This cannot be undone.")
                Text("Finish or stop any active cook and its recording first. Removal is refused while a cook is active or a recording is in progress.")
            }
            Section("Server account") {
                Text("This does not delete the server account. Signing in again will not bring the journal back. Credentials, deletion records and recovery, and community block and report device state stay untouched.")
            }
            Section {
                Toggle("I understand the journal on this device is removed and my account stays", isOn: $acknowledged).accessibilityIdentifier("local-journal-acknowledge")
                TextField("Type REMOVE", text: $typed).textInputAutocapitalization(.characters).autocorrectionDisabled().focused($typing).accessibilityIdentifier("local-journal-confirmation")
                Button("Remove local journal", role: .destructive) { typing = false; confirm() }.disabled(!acknowledged || typed != "REMOVE").accessibilityIdentifier("local-journal-submit")
            }
        }.navigationTitle("Remove local journal").navigationBarTitleDisplayMode(.inline).scrollDismissesKeyboard(.interactively)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }; ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Hide keyboard") { typing = false } } }
    }
}

private struct McpDeviceActionsSection: View {
    let device: NativeMcpDeviceActions
    @State private var confirmingUnregister = false
    /// PL-14 (#172): the section header's status (DD-2), mirroring Android.
    private var deviceStatus: String {
        if !device.available { return "Not available yet" }
        return device.registered ? "On — this phone accepts requests" : "Off"
    }
    var body: some View {
        Section {
            Text("An assistant can ask this iPhone to do small things in the app — never to control a grill. Requests wait here until you open PitTech; nothing runs in the background and nothing counts as done until this phone reports it. Choose which kinds of requests this phone accepts.").font(.footnote)
            if !device.available {
                Text("Device actions aren't available yet.").accessibilityIdentifier("mcp-device-unavailable")
            } else {
                ForEach(mcpDeviceClasses, id: \.self) { cls in
                    Toggle(mcpDeviceClassLabel(cls), isOn: Binding(
                        get: { device.classes.contains(cls) },
                        set: { device.setClass(cls, $0) }
                    )).accessibilityIdentifier("mcp-device-class-\(cls)")
                }
                Button("Check for requests") { Task { await device.checkNow() } }
                    .disabled(device.busy)
                    .accessibilityIdentifier("mcp-device-check")
                if let summary = device.lastCheckSummary {
                    Text(summary).font(.footnote).accessibilityIdentifier("mcp-device-last-check")
                }
                if device.registered {
                    Button("Stop taking requests", role: .destructive) { confirmingUnregister = true }
                        .disabled(device.busy)
                        .accessibilityIdentifier("mcp-device-unregister")
                }
                if device.busy { ProgressView() }
                if let message = device.message { Text(message).accessibilityIdentifier("mcp-device-message") }
            }
        } header: {
            AccountSectionHeader(title: "Device actions", status: deviceStatus, statusId: "account-panel-device-status")
        }
        .task { await device.refresh() }
        .confirmationDialog("Stop taking requests?", isPresented: $confirmingUnregister) {
            Button("Stop", role: .destructive) { Task { await device.unregister() } }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("This phone stops accepting assistant requests, and any waiting requests for it are canceled. You can turn a class back on any time.")
        }
    }
}
