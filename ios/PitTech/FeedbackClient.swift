import Foundation

struct NativeFeedbackSubmission: Codable {
    var kind: String
    var title: String
    var description: String
    var appVersion: String
    var platform = "ios"
    var osVersion: String
    var device: String
}

enum NativeFeedbackError: LocalizedError {
    case invalid, unavailable, limited, failed
    var errorDescription: String? {
        switch self {
        case .invalid: "Shorten the report and try again."
        case .unavailable: "Feedback is temporarily unavailable. Your draft is saved."
        case .limited: "Too many reports were submitted recently. Please try later."
        case .failed: "Couldn't submit feedback. Check your connection and try again."
        }
    }
}

enum NativeFeedbackClient {
    static func submit(_ value: NativeFeedbackSubmission, session: URLSession = .shared) async throws -> Int? {
        guard ["BUG", "FEATURE"].contains(value.kind), !value.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              value.title.count <= 120, value.description.count <= 1500 else { throw NativeFeedbackError.invalid }
        var request = URLRequest(url: URL(string: "https://pittech-feedback-relay-production.up.railway.app/v1/feedback")!)
        request.httpMethod = "POST"; request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONEncoder().encode(value)
        let (data, response): (Data, URLResponse)
        do { (data, response) = try await session.data(for: request) }
        catch { throw NativeFeedbackError.failed }
        guard let response = response as? HTTPURLResponse, data.count <= 65536 else { throw NativeFeedbackError.failed }
        switch response.statusCode {
        case 200..<300: return (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["issueNumber"] as? Int
        case 400, 413: throw NativeFeedbackError.invalid
        case 429: throw NativeFeedbackError.limited
        case 503: throw NativeFeedbackError.unavailable
        default: throw NativeFeedbackError.failed
        }
    }
}
