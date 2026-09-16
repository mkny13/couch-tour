import re

with open('app/src/test/java/dev/mike/couchtour/DiscoveryCatalogE2ETest.kt', 'r') as f:
    content = f.read()

def replacer(match):
    prefix = match.group(1)
    body = match.group(2)
    # remove db.close() if present
    body = re.sub(r'^\s*db\.close\(\)\s*$', '', body, flags=re.MULTILINE)
    # indent body
    indented = '\n'.join(prefix + '    ' + line.lstrip() if line.strip() else line for line in body.split('\n'))
    # remove trailing empty lines
    while indented.endswith('\n'):
        indented = indented[:-1]
    return prefix + 'SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->\n' + indented + '\n' + prefix + '}'

pattern = r'^(\s*)val db = SQLiteDatabase\.openOrCreateDatabase\(dbFile, null\)\n(.*?(?=\n\s*\}\n|\n\s*\}\s*$))'
# Since we are matching per test function block, the lookahead (?=\n\s*\}) works well.
content = re.sub(pattern, replacer, content, flags=re.DOTALL | re.MULTILINE)

with open('app/src/test/java/dev/mike/couchtour/DiscoveryCatalogE2ETest.kt', 'w') as f:
    f.write(content)
