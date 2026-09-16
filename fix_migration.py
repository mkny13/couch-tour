import re

with open('app/src/test/java/dev/mike/couchtour/MigrationTest.kt', 'r') as f:
    content = f.read()

def replace_func(match):
    body = match.group(2)
    # remove db.close() from body
    body = re.sub(r'^\s*db\.close\(\)\s*$', '', body, flags=re.MULTILINE)
    # indent body by 4 spaces
    indented = '\n'.join('    ' + line if line.strip() else line for line in body.split('\n'))
    # remove trailing empty lines
    while indented.endswith('\n    ') or indented.endswith('\n'):
        indented = indented[:-1]
    return match.group(1) + 'SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->\n' + indented + '\n    }'

pattern = r'(private fun createV\d+DatabaseWithRows\(\) \{\n)\s*val db = SQLiteDatabase\.openOrCreateDatabase\(dbFile, null\)\n(.*?\n    \})'
content = re.sub(pattern, replace_func, content, flags=re.DOTALL)

with open('app/src/test/java/dev/mike/couchtour/MigrationTest.kt', 'w') as f:
    f.write(content)
