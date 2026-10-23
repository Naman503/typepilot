import Foundation

/// What we know about the focused text field (read through the Accessibility API by the app).
public struct FieldContext: Sendable, Equatable {
    public var appName: String
    public var bundleID: String
    public var windowTitle: String
    /// Label, title, description or placeholder of the field.
    public var fieldLabel: String
    public var textBefore: String
    public var textAfter: String
    public var selectedText: String
    public var isMultiline: Bool
    /// Visible text around the field (e.g. the message being replied to), most recent last.
    public var screenContext: String

    public init(appName: String = "", bundleID: String = "", windowTitle: String = "", fieldLabel: String = "",
                textBefore: String = "", textAfter: String = "", selectedText: String = "", isMultiline: Bool = false,
                screenContext: String = "") {
        self.appName = appName
        self.bundleID = bundleID
        self.windowTitle = windowTitle
        self.fieldLabel = fieldLabel
        self.textBefore = textBefore
        self.textAfter = textAfter
        self.selectedText = selectedText
        self.isMultiline = isMultiline
        self.screenContext = screenContext
    }
}

public enum SuggestTrigger: Sendable { case typing, focus }

public struct Suggestion: Sendable, Equatable {
    public enum Source: String, Sendable { case memory, cache, ai }
    public var text: String
    public var source: Source
    public var latencyMs: Int
    public var model: String?
    /// Characters before the caret to replace (a typed shortcut like ";addr").
    public var replaceLength: Int
    /// Label shown in the pill (e.g. the shortcut or "Your usual Address").
    public var note: String?

    public init(text: String, source: Source, latencyMs: Int, model: String? = nil, replaceLength: Int = 0, note: String? = nil) {
        self.text = text
        self.source = source
        self.latencyMs = latencyMs
        self.model = model
        self.replaceLength = replaceLength
        self.note = note
    }
}
