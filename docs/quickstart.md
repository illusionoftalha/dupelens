# Try DupeLens with sample files

Download `dupelens-1.3.0.jar` and `SHA256SUMS.txt` from [the release](https://github.com/illusionoftalha/dupelens/releases/tag/v1.3.0), verify the checksum as described in the [README](../README.md#verify-a-download), and open a terminal in the JAR's folder. You need Java 17 or newer (`java -version`).

These commands create a fresh temporary folder containing three identical synthetic files. They do not scan your personal folders or invoke Trash.

## Windows PowerShell

```powershell
$demo = Join-Path ([IO.Path]::GetTempPath()) ('dupelens-demo-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $demo 'sample-folder/node_modules') -Force | Out-Null
Set-Content (Join-Path $demo 'sample-folder/notes.txt') 'DupeLens sample'
Copy-Item (Join-Path $demo 'sample-folder/notes.txt') (Join-Path $demo 'sample-folder/notes-copy.txt')
Copy-Item (Join-Path $demo 'sample-folder/notes.txt') (Join-Path $demo 'sample-folder/node_modules/cached.txt')
java -jar .\dupelens-1.3.0.jar (Join-Path $demo 'sample-folder')
java -jar .\dupelens-1.3.0.jar (Join-Path $demo 'sample-folder') --exclude node_modules --html (Join-Path $demo 'report.html')
Write-Output "Open this report in your browser: $demo/report.html"
```

## Linux or macOS

```sh
demo=$(mktemp -d)
mkdir -p "$demo/sample-folder/node_modules"
printf 'DupeLens sample\n' > "$demo/sample-folder/notes.txt"
cp "$demo/sample-folder/notes.txt" "$demo/sample-folder/notes-copy.txt"
cp "$demo/sample-folder/notes.txt" "$demo/sample-folder/node_modules/cached.txt"
java -jar dupelens-1.3.0.jar "$demo/sample-folder"
java -jar dupelens-1.3.0.jar "$demo/sample-folder" --exclude node_modules --html "$demo/report.html"
printf 'Open this report in your browser: %s/report.html\n' "$demo"
```

## Expected result

The first scan reports **3 files and 1 duplicate group**. The second reports **2 files and 1 duplicate group** because `node_modules` is excluded. The new `report.html` opens offline and has no deletion controls. All three input files remain unchanged. Byte totals vary with line endings and encoding.

No folders are excluded unless requested. Reusing an existing report filename fails instead of replacing it. Keep the temporary folder for inspection, and remove it manually when finished.

For an interactive workspace, see [browser review](../README.md#browser-review-and-deletion). It requires explicit confirmation before moving selected copies to system Trash; the CLI above never moves files.
