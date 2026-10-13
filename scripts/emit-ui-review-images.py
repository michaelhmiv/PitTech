#!/usr/bin/env python3
"""Expose synthetic CI captures to remote visual review; original artifacts stay unchanged."""
import base64
import json
import pathlib
import sys

images = {}
for supplied in sys.argv[1:]:
    path = pathlib.Path(supplied)
    if path.is_file():
        images[path.name] = path
    elif path.is_dir():
        manifest = path / "manifest.json"
        if manifest.exists():
            for case in json.loads(manifest.read_text()):
                for attachment in case.get("attachments", []):
                    name = attachment.get("suggestedHumanReadableName", "")
                    if name.startswith("Native "):
                        images[name.split("_0_")[0]] = path / attachment["exportedFileName"]
        else:
            images.update((image.name, image) for image in path.glob("*.png"))

print("::group::Visual review images")
for name, path in sorted(images.items()):
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        continue
    encoded = base64.b64encode(data).decode("ascii")
    label = name.replace("|", "/")
    print("PITTECH_REVIEW_IMAGE_BEGIN|" + label)
    for offset in range(0, len(encoded), 32768):
        print("PITTECH_REVIEW_IMAGE_PART|" + encoded[offset:offset + 32768])
    print("PITTECH_REVIEW_IMAGE_END|" + label)
print("::endgroup::")
