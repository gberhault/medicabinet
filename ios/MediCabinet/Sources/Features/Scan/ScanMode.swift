/// What a recognised code does. Always `.add` when the Scan tab opens and at launch: Remove is never remembered
/// (requirement R1), so a box is never taken out by a scan the user meant as an intake.
enum ScanMode: Hashable {
    case add
    case remove
}
