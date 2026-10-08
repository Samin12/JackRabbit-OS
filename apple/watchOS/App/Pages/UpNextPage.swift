import SamRabbitKit
import SwiftUI

/// Page 4: the next events on the calendar.
struct UpNextPage: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        TimelineView(.everyMinute) { context in
            let events = model.events(now: context.date)
            ScrollView {
                VStack(spacing: 7) {
                    if events.isEmpty {
                        EmptyNote(symbol: "calendar", title: "Nothing else today",
                                  detail: model.summary?.calendar.available == false ? "Calendar isn't connected." : nil,
                                  tint: SamTheme.violet)
                    } else {
                        ForEach(Array(events.enumerated()), id: \.element.id) { index, event in
                            EventCard(event: event, now: context.date, first: index == 0)
                        }
                    }
                }
                .padding(.horizontal, 2)
            }
        }
        .navigationTitle("Up next")
        .withBanner()
        .samPage(SamTheme.violet)
    }
}

struct EventCard: View {
    let event: CalendarEvent
    let now: Date
    let first: Bool

    var body: some View {
        let live = event.isNow(now)
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 5) {
                Text(live ? "Now" : Formatting.until(event.startsAt, end: event.endsAt, now: now, short: true))
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(live ? SamTheme.green : SamTheme.violet)
                Spacer(minLength: 2)
                Text(Formatting.range(event.startsAt, event.endsAt, allDay: event.allDay, now: now))
                    .font(.system(size: 11.5))
                    .foregroundStyle(SamTheme.muted)
                    .lineLimit(1)
            }
            Text(event.title)
                .font(.system(size: first ? 16 : 15, weight: .semibold))
                .foregroundStyle(SamTheme.ink)
                .lineLimit(2)
            if let location = event.location, !location.isEmpty {
                Label(location, systemImage: "mappin").font(.system(size: 12)).foregroundStyle(SamTheme.ink2).lineLimit(1)
            } else if event.meetingUrl != nil {
                Label("Video call", systemImage: "video.fill").font(.system(size: 12)).foregroundStyle(SamTheme.ink2)
            }
        }
        .watchCard(tint: first ? SamTheme.violet : nil)
    }
}
