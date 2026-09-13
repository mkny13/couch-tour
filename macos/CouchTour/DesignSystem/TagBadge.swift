import CouchTourKit
import SwiftUI

struct TagBadge: View {
    let tag: Tag
    let action: (() -> Void)?
    
    init(_ tag: Tag, action: (() -> Void)? = nil) {
        self.tag = tag
        self.action = action
    }
    
    var body: some View {
        let content = Text(tag.name)
            .font(.caption2.bold())
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.secondary.opacity(0.18), in: RoundedRectangle(cornerRadius: 4))
            .foregroundStyle(Color.secondary)
            
        if let action = action {
            Button(action: action) {
                content
            }
            .buttonStyle(.plain)
            .onHover { hovering in
                #if os(macOS)
                if hovering {
                    NSCursor.pointingHand.push()
                } else {
                    NSCursor.pop()
                }
                #endif
            }
        } else {
            content
        }
    }
}
