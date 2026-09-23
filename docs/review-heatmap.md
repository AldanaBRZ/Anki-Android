# Review heatmap

The deck list includes a review heatmap underneath your decks. Scroll past the decks to see it, use the year controls to browse your history, and select a day. Choose **View reviewed cards** (or **View due cards** for a future day) to inspect its cards in the browser.

This is a native Android implementation inspired by the desktop [Review Heatmap add-on](https://github.com/glutanimate/review-heatmap). It works offline with the review history already stored in your collection and does not need an AnkiQuest account. Sync your collection normally to include reviews made on your other Anki devices.

The calendar shows daily review totals. The summary reports total reviews, days studied, your current and longest streaks, and average reviews per study day. A streak ending yesterday stays current until today's study day ends, so it does not disappear before you have had a chance to study. These streaks count days with actual answers; AnkiQuest streak freezes do not extend them. All retained history contributes to the summary, regardless of the selected calendar year.

A review means an answered card: answering the same card several times counts several reviews. The browser shows each surviving card once, so its result count can be smaller than the number of reviews on a day. Retained history from deleted cards still counts in the heatmap. Manual scheduling changes do not count as studying.

Study days follow Anki's day rollover and native day-search boundaries, rather than always changing at midnight. The heatmap includes all decks even when the deck list is filtered.

Gray future squares show cards currently scheduled over the next 90 days, using the same due-day rules as Anki's browser. This is an estimate: studying, rescheduling, changing settings, and syncing can change the numbers. It does not predict new cards you will introduce or repeated future learning steps, and excludes suspended and buried cards. Select a forecast day and choose **View due cards** to inspect the cards currently scheduled for that day.

The heatmap refreshes with the deck list after reviewing or syncing. An empty collection with only the Default deck keeps the existing welcome screen; the calendar appears once the deck list is shown.
