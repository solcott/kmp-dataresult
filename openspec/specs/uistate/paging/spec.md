# uistate/paging Specification

## Purpose

Lets a screen show a list that a cursor-paged source loads a page at a time. Pages fold into one
paged view state under the same stale-while-revalidate rules `ContentState` keeps for single
values, and a retained holder requests pages, retries what failed and refreshes.

## Requirements

### Requirement: Pages fold into one paged state
The paged state SHALL fold each emission for a page (loading, a page, or a failure) into one state.
That state holds the items loaded so far in page order, the first page's header (`meta`), the
origin of the first page, a refresh status, an append status, and whether the end has been reached.

#### Scenario: First page arrives
- **WHEN** the state has loaded nothing and the first page arrives from the network with items A, B, a header H and a next cursor
- **THEN** the items are A, B, the header is H, the origin is network, the refresh status is idle and the end is not reached

#### Scenario: Last page arrives
- **WHEN** a page arrives with no next cursor
- **THEN** the end is reached

#### Scenario: Nothing loaded yet
- **WHEN** the state has been created and no page has arrived
- **THEN** it reports that nothing has loaded, its items are empty, its header is absent and its refresh status is loading

### Requirement: An empty list is an answer
The paged state SHALL count as loaded once any first page has arrived, including a first page with
no items. Whether anything has loaded SHALL NOT be judged from whether the items are empty.

#### Scenario: Empty first page from the network
- **WHEN** the first page arrives from the network with no items and no next cursor
- **THEN** the state reports that it has loaded, its items are empty and the end is reached

### Requirement: The header comes from the first page
The header SHALL be taken from the first page only. Later pages' headers SHALL be ignored, and a new
first page (a refresh, or a page re-emitted from its source) SHALL replace the header.

#### Scenario: Later page carries a different header
- **WHEN** the first page set header H and the second page arrives with header H2
- **THEN** the header is still H

### Requirement: Items are de-duplicated by id
The paged state SHALL keep at most one item per id, as given by an id function the caller supplies.
When an id appears again on a later page, the first occurrence SHALL be kept in its place and the
repeat dropped.

#### Scenario: An item moves between pages
- **WHEN** the items are A, B, C and the next page arrives with C, D
- **THEN** the items are A, B, C, D

### Requirement: Requesting the next page
Loading more SHALL request the page after the last loaded page's next cursor and set the append
status to loading while the refresh status and the items stay as they are. When that page arrives,
its items SHALL be appended and the append status SHALL be idle.

#### Scenario: Second page loads
- **WHEN** the items are A, B with a next cursor, and load more is called and the page after arrives with C, D
- **THEN** while it is in flight the append status is loading and the items are A, B; afterwards the items are A, B, C, D and the append status is idle

### Requirement: Load more is ignored when it cannot proceed
Loading more SHALL do nothing while a page is loading, while the refresh is in flight, before the
first page has arrived for the current key, after a page has failed until it is retried, and once
the end is reached.

#### Scenario: Load more while a page loads
- **WHEN** a next page is in flight and load more is called again
- **THEN** no second request is made

#### Scenario: Load more at the end
- **WHEN** the end is reached and load more is called
- **THEN** no request is made and the append status stays idle

#### Scenario: Load more after a failed page
- **WHEN** the last next-page request failed and load more is called
- **THEN** no request is made and the append status stays failed

### Requirement: A failed page keeps the earlier pages
When a next-page request fails, the paged state SHALL keep every item loaded so far, set the append
status to failed with the reason, and leave the refresh status unchanged.

#### Scenario: Third page fails
- **WHEN** two pages are loaded and the request for the third fails with a network error
- **THEN** the items of both pages are still shown, the append status is failed with a network error, and the refresh status is idle

### Requirement: A failed refresh keeps the items
When a request for the first page fails, the paged state SHALL keep the items, header and origin it
holds, set the refresh status to failed with the reason, and leave the append status unchanged.

#### Scenario: Refresh fails after pages loaded
- **WHEN** two pages are loaded and a refresh fails
- **THEN** the items of both pages, the header and the origin are unchanged and the refresh status is failed

#### Scenario: First load fails
- **WHEN** nothing has loaded and the first page fails
- **THEN** the state still reports nothing loaded, its items are empty and the refresh status is failed

### Requirement: Retry repeats only what failed
Retrying SHALL repeat the request that failed and nothing else. If the refresh failed, it SHALL
request the first page again. Otherwise, if a next page failed, it SHALL request that page again
from the same cursor, keeping the earlier pages. If nothing failed, it SHALL do nothing.

#### Scenario: Retry after a failed page
- **WHEN** two pages are loaded, the third failed, and retry is called
- **THEN** only the third page is requested again, from the second page's next cursor, and the items of the first two pages stay shown

#### Scenario: Retry after a failed refresh
- **WHEN** the refresh failed and retry is called
- **THEN** the first page is requested again and the items stay shown while it loads

#### Scenario: Retry with nothing failed
- **WHEN** no request has failed and retry is called
- **THEN** no request is made

### Requirement: Refresh reloads from the first page
Refreshing SHALL cancel any page in flight, request the first page again and set the refresh status
to loading, keeping the items. When the first page arrives, it SHALL replace every loaded page, and
paging SHALL continue from its next cursor.

#### Scenario: Refresh succeeds
- **WHEN** three pages are loaded, refresh is called, and the first page arrives with items X, Y and a next cursor
- **THEN** the items are X, Y, the end is not reached, and the next load more requests the page after X, Y

#### Scenario: Refresh cancels a page in flight
- **WHEN** a next page is in flight and refresh is called
- **THEN** that page's result is discarded and the append status is idle

### Requirement: Every emission settles its status
The status of a request SHALL settle on every page or failure that arrives, without waiting for the
source to complete. A request whose source completes without emitting anything SHALL settle to
idle. A request abandoned for a newer one SHALL NOT be reported as settled.

#### Scenario: Source completes without emitting
- **WHEN** a next-page request's source completes having emitted nothing
- **THEN** the append status becomes idle and the items are unchanged

### Requirement: A new key keeps the items when asked
When the holder's key changes and the caller chose to keep items, the paged state SHALL keep the
previous key's items and header until the new key's first page arrives or fails, with the refresh
status loading. Any request for the previous key SHALL be cancelled and its results discarded.

#### Scenario: Search query changes
- **WHEN** results for "ann" are shown and the key changes to "anna" with keep chosen
- **THEN** the "ann" results stay shown with the refresh status loading until the "anna" first page arrives and replaces them

#### Scenario: Old page arrives after key change
- **WHEN** a next page for the previous key is in flight and the key changes
- **THEN** that page is never added to the items

#### Scenario: Load more before the new key settles
- **WHEN** the key has changed with keep chosen and its first page has not arrived
- **THEN** load more makes no request

### Requirement: A new key clears the items when asked
When the holder's key changes and the caller chose to clear items, the paged state SHALL drop the
items, header and origin at once, report nothing loaded, and set the refresh status to loading.

#### Scenario: Filter changes
- **WHEN** a filtered list is shown and the key changes with clear chosen
- **THEN** the items are empty, nothing is reported loaded, and the refresh status is loading until the new first page settles

### Requirement: A list reached again shows its pages at once
The holder SHALL retain its pages while its screen is off the back stack top or recreated, and show
them at once when reached again without requesting them again. A request that was interrupted by
leaving SHALL be requested again on return.

#### Scenario: Returning to a loaded list
- **WHEN** three pages are loaded, the user navigates away and comes back with the same key
- **THEN** the three pages show immediately and no page is requested

#### Scenario: Returning while a page was loading
- **WHEN** a next page was in flight when the user navigated away
- **THEN** on return that page is requested again and the append status is loading

### Requirement: A page re-emitted by its source replaces that page
A page's source MAY emit more than once, such as a cached read followed by the network, or a
database watcher. Each emission SHALL replace that page's items in place. If its next cursor is
unchanged, later pages SHALL be kept. If it changed, later pages SHALL be dropped.

#### Scenario: Cached first page then network
- **WHEN** the first page arrives from the cache with A, B and then from the network with A, B, C and the same next cursor
- **THEN** the items are A, B, C, the origin is network, and pages loaded after it are kept

#### Scenario: Re-emitted first page changes its cursor
- **WHEN** two pages are loaded and the first page's source emits again with a different next cursor
- **THEN** the second page is dropped and the next load more requests from the new cursor

### Requirement: An empty cached first page is not an answer
While the first page's request is loading or has failed, an empty first page read from the cache
SHALL NOT count as an answer, so a screen does not show "nothing here" for a cache miss. A settled
empty cached first page SHALL count as an answer.

#### Scenario: Empty cache then network still loading
- **WHEN** the first page arrives from the cache with no items while its request is still loading
- **THEN** the state does not report an answer

#### Scenario: Empty cache and the network fails
- **WHEN** the first page arrived empty from the cache and the network request then fails
- **THEN** the state does not report an answer and the refresh status is failed

#### Scenario: Empty cache is all there is
- **WHEN** the first page arrives empty from the cache and its request settles without another page
- **THEN** the state reports an answer

### Requirement: Optimistic edits to the items
The holder SHALL let the caller transform the items shown, such as removing an item after unblocking
it, without a request. The edit SHALL stay until a refresh or a new key, or until the source
re-emits the page holding the edited items, which SHALL replace that page's items.

#### Scenario: Remove an item
- **WHEN** the items are A, B, C and the caller removes B
- **THEN** the items are A, C and no request is made

#### Scenario: Edit survives loading more
- **WHEN** the caller removed B and the next page then arrives with D
- **THEN** the items are A, C, D

### Requirement: A disabled holder loads nothing
While the caller marks the holder disabled, it SHALL hold no items, report nothing loaded, show no
loading status, and make no request. Enabling it SHALL request the first page.

#### Scenario: Empty search query
- **WHEN** the holder is disabled because the query is blank
- **THEN** no request is made, the items are empty and neither status is loading

#### Scenario: Enabled again
- **WHEN** the holder becomes enabled
- **THEN** the first page is requested and the refresh status is loading

### Requirement: A holder can start from a page it already has
The holder SHALL accept an initial page, such as the first replies that came with a comment thread.
When given one, it SHALL start loaded with that page's items, header, origin and next cursor, and
request nothing until asked to load more or refresh.

#### Scenario: Thread replies seeded from the thread
- **WHEN** a holder starts with an initial page of replies R1, R2 and a next cursor
- **THEN** the items are R1, R2, nothing is requested, and load more requests the page after R2

### Requirement: The paged state exports to Swift
The paged state, its page type, its statuses and the key-change choice SHALL be usable from Swift
through Swift export, as `ContentState` is. They SHALL use only sealed classes or enums for closed
hierarchies, `List` for collections, and no Compose types.

#### Scenario: Swift export check
- **WHEN** the `uistate` module's checks run
- **THEN** the Swift export check passes with the paging API in place
