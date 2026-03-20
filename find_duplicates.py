import csv
from collections import OrderedDict
from pathlib import Path


def _normalize_row(row):
    return [cell.strip() for cell in row]


def _normalize_cell(cell):
    return cell.replace("\ufeff", "").strip()


def _normalize_key(row):
    padded = list(row) + [""] * (4 - len(row))
    repo = _normalize_cell(padded[1]).lower()
    queries = _normalize_cell(padded[2])
    annotations = _normalize_cell(padded[3])
    return (repo, queries, annotations)


def _is_header(row):
    normalized = [_normalize_cell(cell).lower() for cell in row]
    return normalized[:2] == ["date", "repository name"]


def find_and_remove_duplicates(file_path):
    file_path = Path(file_path)
    with file_path.open('r', newline='') as f:
        reader = csv.reader(f)
        header = None
        # Using OrderedDict to keep the original order and remove duplicates
        unique_rows = OrderedDict()
        duplicates_count = 0

        for row in reader:
            if not row or all(not cell.strip() for cell in row):
                continue
            row = _normalize_row(row)
            if _is_header(row):
                if header is None:
                    header = row
                # Skip repeated headers anywhere in the file.
                continue

            if header is None:
                # Skip unexpected leading lines (e.g., "UNKNOWN") before header.
                continue

            key = _normalize_key(row)
            if key in unique_rows:
                unique_rows[key]["count"] += 1
                duplicates_count += 1
            else:
                unique_rows[key] = {"count": 1, "row": row}

    if header is None:
        print("No header found; nothing to process.")
        return

    if duplicates_count == 0:
        print("No duplicated rows found.")
        return

    print(
        f"Found {duplicates_count} duplicated rows across "
        f"{len([k for k, v in unique_rows.items() if v['count'] > 1])} unique records."
    )

    # Save the cleaned data to a new file first for safety
    output_file = file_path.with_name(file_path.stem + "_cleaned" + file_path.suffix)
    with output_file.open('w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(header)
        for entry in unique_rows.values():
            writer.writerow(entry["row"])

    print(f"Cleaned CSV saved to: {output_file}")

    # Overwrite the original file if requested
    print("To replace the original file with the cleaned one, run:")
    print(f"mv {output_file} {file_path}")


if __name__ == "__main__":
    csv_file = "/home/raditha/csi/Antikythera/antikythera-examples/query-optimization-stats.csv"
    find_and_remove_duplicates(csv_file)
