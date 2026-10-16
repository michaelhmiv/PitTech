import SwiftUI

struct NativeAccountView: View {
    @Environment(NativeAccountCoordinator.self) private var account
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var code = ""
    @State private var confirmingSignOut = false
    @State private var reviewingDeletion = false
    private enum Field: Hashable { case email, code }
    @FocusState private var focused: Field?
    var body: some View {
        Form {
            Section { Text("Your cookbook works without an account. Signing in doesn't publish or upload your local records.") }
            if !account.enabled {
                Section { Text("Accounts arrive with community recipes and private sync.").accessibilityIdentifier("account-unavailable") }
            } else if let deletion = account.deletionRecord {
                Section("Account deletion") {
                    Text(deletion.receipt?.description ?? "The deletion response was not confirmed. Recover the request before retrying.").accessibilityIdentifier("account-deletion-status")
                    Text("Your local journal stays available on this device. Deletion does not stop your local cooking.")
                    if deletion.receipt?.state == "complete" {
                        Button("Close completed request") { account.dismissCompletedDeletion() }.disabled(account.busy)
                    } else {
                        if deletion.receipt != nil { Button("Check deletion progress") { Task { await account.checkDeletion() } }.disabled(account.busy).accessibilityIdentifier("account-deletion-check") }
                        Button("Recover deletion request") { Task { await account.recoverDeletion() } }.disabled(account.busy).accessibilityIdentifier("account-deletion-recover")
                        if deletion.receipt == nil && account.signedIn { Button("Review and retry the same request") { reviewingDeletion = true }.disabled(account.busy).accessibilityIdentifier("account-deletion-retry") }
                    }
                }
                if deletion.receipt == nil && !account.signedIn {
                    Section { Text("Verify the original account email to retry this device’s saved request. Another account cannot take over the request.") }
                    signInContent
                }
            } else if account.signedIn {
                Section("Account") {
                    Text("Signed in").accessibilityIdentifier("account-signed-in")
                    Button("Check connection") { Task { await account.checkSession() } }.disabled(account.busy)
                    Button("Sign out", role: .destructive) { confirmingSignOut = true }.disabled(account.busy)
                    Button("Delete account and server data", role: .destructive) { reviewingDeletion = true }.disabled(account.busy).accessibilityIdentifier("account-delete")
                }
            } else { signInContent }
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
        .confirmationDialog("Sign out on this device?", isPresented: $confirmingSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { Task { await account.signOut() } }.accessibilityIdentifier("account-confirm-signout")
        } message: { Text("Your local cooks, recipes and photos stay on this device. Signing out doesn't delete your account.") }
    }
    @ViewBuilder private var signInContent: some View {
        Section("Sign in by email") {
            TextField("Email address", text: $email).textContentType(.emailAddress).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled().focused($focused, equals: .email).privacySensitive().accessibilityIdentifier("account-email")
            Button("Send sign-in email") { focused = nil; Task { await account.start(email) } }.disabled(account.busy || email.isEmpty).accessibilityIdentifier("account-start")
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
