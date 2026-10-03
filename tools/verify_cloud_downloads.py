"""Read connector-provided HTTPS downloads, compare bytes, and emit token-free receipts.

Input JSON comes from stdin; signed download URLs are never persisted or printed.
Downloaded data is hashed in memory and discarded, not written over originals.
"""
import concurrent.futures
import hashlib
import json
import sys
import urllib.error
import urllib.parse
import urllib.request


def verify(item):
    result = {k: item[k] for k in ("id", "relative_path", "size_bytes", "sha256")}
    try:
        parsed = urllib.parse.urlsplit(item["download_url"])
        host = (parsed.hostname or "").lower()
        if parsed.scheme != "https" or parsed.username or parsed.password or not (
            host.endswith(".oaiusercontent.com") or host == "oaiusercontent.com"
        ):
            raise ValueError("Unexpected connector download host")
        digest = hashlib.sha256()
        count = 0
        with urllib.request.urlopen(item["download_url"], timeout=60) as response:
            while block := response.read(1024 * 1024):
                count += len(block)
                if count > item["size_bytes"]:
                    raise ValueError("Downloaded file exceeds expected length")
                digest.update(block)
        if count != item["size_bytes"] or digest.hexdigest() != item["sha256"]:
            raise ValueError("Downloaded bytes do not match local original")
        result["verified"] = True
    except urllib.error.HTTPError as error:
        result.update(verified=False, error=f"HTTP {error.code}")
    except Exception as error:
        # Exception text can contain a signed URL. Only print a safe category.
        result.update(verified=False, error=type(error).__name__)
    return result


def main():
    items = json.load(sys.stdin)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(verify, items))
    print(json.dumps(results))
    return 0 if all(item["verified"] for item in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
