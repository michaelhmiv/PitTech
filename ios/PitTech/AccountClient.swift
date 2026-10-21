import Foundation
import AuthenticationServices
import UIKit

protocol NativeAccountAPI { func call(_ path: String, body: [String: String]?, access: String?) async throws -> Data }
struct NativeAccountClient: NativeAccountAPI {
    var configuration: URLSessionConfiguration?
    func call(_ path: String, body: [String: String]? = nil, access: String? = nil) async throws -> Data {
        guard ["/v1/auth/challenges", "/v1/auth/verify", "/v1/auth/refresh", "/v1/auth/logout", "/v1/me", "/v1/me/deletion", "/v1/deletion/recover", "/v1/deletion/status", "/v1/auth/provider/challenges", "/v1/auth/provider/verify", "/v1/me/identities"].contains(path) else { throw NativeAccountError.invalid }
        var request = URLRequest(url: URL(string: "\(NativeOnlineConfiguration.current.apiOrigin)" + path)!)
        request.httpMethod = body == nil ? "GET" : "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        guard (request.httpBody?.count ?? 0) <= 8192 else { throw NativeAccountError.invalid }
        if let access { guard NativeAccountProtocol.credential(access) else { throw NativeAccountError.invalid }; request.setValue("Bearer " + access, forHTTPHeaderField: "Authorization") }
        do {
            let result = try await NativeRelayTransport(configuration: configuration, responseError: { NativeAccountError.status($0) }, maximumResponseBytes: 8192).run(request)
            guard result.data.count <= 8192 else { throw NativeAccountError.invalid }; return result.data
        } catch let error as NativeAccountError { throw error }
        catch is CancellationError { throw CancellationError() }
        catch { throw NativeAccountError.network }
    }
}

/// User dismissal of the Apple sheet. Distinct from CancellationError so the
/// coordinator stays silent: no error, no message, pending state retained.
struct AppleAuthorizationCancelled: Error {}

/// Sign in with Apple behind an injectable seam. The nonce is the SHA-256 proof
/// hash; the identity token returns as a UTF-8 string and the authorization
/// code is discarded. No test contacts Apple: tests inject a fake.
protocol AppleAuthorizing {
    @MainActor func authorize(nonce: String) async throws -> String
}

final class NativeAppleAuthorizer: NSObject, AppleAuthorizing {
    private var controller: ASAuthorizationController?
    private var continuation: CheckedContinuation<String, Error>?
    @MainActor func authorize(nonce: String) async throws -> String {
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.email]
        request.nonce = nonce
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        self.controller = controller
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
            self.continuation = continuation
            controller.performRequests()
        }
    }
}
extension NativeAppleAuthorizer: ASAuthorizationControllerDelegate {
    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        let continuation = self.continuation
        self.continuation = nil; self.controller = nil
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let data = credential.identityToken,
              let token = String(data: data, encoding: .utf8), !token.isEmpty else {
            continuation?.resume(throwing: NativeAccountError.providerUnavailable)
            return
        }
        continuation?.resume(returning: token)
    }
    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        let continuation = self.continuation
        self.continuation = nil; self.controller = nil
        if (error as? ASAuthorizationError)?.code == .canceled {
            continuation?.resume(throwing: AppleAuthorizationCancelled())
        } else {
            continuation?.resume(throwing: NativeAccountError.providerUnavailable)
        }
    }
}
extension NativeAppleAuthorizer: ASAuthorizationControllerPresentationContextProviding {
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        return windows.first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}
