from pathlib import Path
import json
r = Path(__file__).resolve().parents[1]
a = r / 'app/src/main/assets/grammars'
a.mkdir(parents=True, exist_ok=True)
keywords = {
    'python': 'and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield',
    'typescript': 'abstract as async await break case catch class const continue debugger declare default delete do else enum export extends false finally for from function get if implements import in instanceof interface keyof let module namespace new null of private protected public readonly return set static super switch this throw true try type typeof undefined var void while yield',
    'clangd': 'alignas alignof auto bool break case catch char class concept const constexpr consteval constinit continue decltype default delete do double else enum explicit export extern false float for friend goto if inline int long namespace new noexcept nullptr operator private protected public register reinterpret_cast requires return short signed sizeof static struct switch template this thread_local throw true try typedef typename union unsigned using virtual void volatile wchar_t while',
    'rust': 'as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while',
    'go': 'break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var',
    'java': 'abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for if implements import instanceof int interface long native new null package private protected public record return short static strictfp super switch synchronized this throw throws transient true false try var void volatile while sealed permits',
    'kotlin': 'abstract actual annotation as break by catch class companion const constructor continue crossinline data delegate do dynamic else enum expect external false final finally for fun get if import in infix init inline inner interface internal is lateinit noinline null object open operator out override package private protected public reified return sealed set super suspend tailrec this throw true try typealias val var vararg when where while',
    'bash': 'if then else elif fi for while do done case esac in function select until time coproc export local readonly declare typeset return break continue',
    'html': '', 'css': 'important inherit initial unset var calc', 'json': 'true false null', 'yaml': 'true false null yes no on off',
    'lua': 'and break do else elseif end false for function goto if in local nil not or repeat return then true until while',
    'ruby': 'BEGIN END alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield',
    'php': 'abstract and array as break callable case catch class clone const continue declare default die do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile eval exit extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield true false null',
    'markdown': '', 'sql': 'SELECT FROM WHERE GROUP BY HAVING ORDER LIMIT OFFSET INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE INDEX VIEW DROP ALTER JOIN LEFT RIGHT INNER OUTER ON AS AND OR NOT NULL IS LIKE IN EXISTS CASE WHEN THEN ELSE END UNION ALL DISTINCT PRIMARY KEY REFERENCES DEFAULT BEGIN COMMIT ROLLBACK',
    'docker': 'FROM RUN CMD LABEL MAINTAINER EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL'
}
for language, words in keywords.items():
    patterns = []
    if language == 'python':
        for delimiter in ['"""', "'''"]:
            patterns.append({'name': 'string.quoted.multi', 'begin': delimiter, 'end': delimiter})
    if language in ['python', 'bash', 'yaml', 'ruby', 'docker']:
        patterns.append({'name': 'comment.line', 'match': '#.*$'})
    elif language == 'sql':
        patterns.append({'name': 'comment.line', 'match': '--.*$'})
    elif language == 'lua':
        patterns += [{'name': 'comment.block', 'begin': r'--\[\[', 'end': r'\]\]'}, {'name': 'comment.line', 'match': '--.*$'}]
    elif language == 'html':
        patterns += [{'name': 'comment.block', 'begin': '<!--', 'end': '-->'}, {'name': 'entity.name.tag', 'match': '</?[A-Za-z][A-Za-z0-9:-]*|/?>'}, {'name': 'entity.other.attribute-name', 'match': r'\b[\w:-]+(?=\s*=)'}]
    elif language != 'markdown':
        patterns += [{'name': 'comment.block', 'begin': r'/\*', 'end': r'\*/'}, {'name': 'comment.line', 'match': '//.*$'}]
    if language == 'markdown':
        patterns += [{'name': 'markup.heading', 'match': '^#{1,6} .*'}, {'name': 'string.quoted', 'begin': '```', 'end': '```'}, {'name': 'string.quoted', 'begin': '`', 'end': '`'}, {'name': 'markup.bold', 'match': r'\*\*[^*]+\*\*'}, {'name': 'entity.name.tag', 'match': r'\[[^\]]+\]\([^)]+\)'}]
    else:
        if language == 'json':
            patterns.append({'name': 'support.type.property-name', 'match': r'"(?:[^"\\]|\\.)*"(?=\s*:)'})
        for delimiter in ['"', "'"] + (['`'] if language in ['typescript', 'go', 'bash'] else []):
            patterns.append({'name': 'string.quoted', 'begin': delimiter, 'end': delimiter, 'patterns': [{'name': 'constant.character.escape', 'match': r'\\.'}]})
        if words:
            patterns.append({'name': 'keyword.control', 'match': ('(?i)' if language == 'sql' else '') + r'\b(?:' + '|'.join(words.split()) + r')\b'})
        patterns += [
            {'name': 'constant.numeric', 'match': r'\b(?:0[xX][0-9a-fA-F]+|[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)\b'},
            {'name': 'entity.name.function', 'match': r'\b[A-Za-z_][A-Za-z0-9_]*(?=\s*\()'},
            {'name': 'entity.name.type', 'match': r'\b[A-Z][A-Za-z0-9_]*\b'},
            {'name': 'keyword.operator', 'match': r'[+\-*/%=!<>|&^~?:]+'}
        ]
    grammar = {'name': language, 'scopeName': 'source.aster.' + language, 'patterns': patterns}
    (a / (language + '.json')).write_text(json.dumps(grammar, ensure_ascii=False, indent=2))
    config = {'brackets': [['{', '}'], ['[', ']'], ['(', ')']], 'autoClosingPairs': [['{', '}'], ['[', ']'], ['(', ')'], ['"', '"'], ["'", "'"]], 'surroundingPairs': [['{', '}'], ['[', ']'], ['(', ')'], ['"', '"']], 'comments': {'lineComment': '#' if language in ['python', 'bash', 'yaml', 'ruby', 'docker'] else '//'}, 'indentationRules': {'increaseIndentPattern': r'^.*(?:\{[^}]*|\([^)]*|\[[^\]]*|:)\s*$', 'decreaseIndentPattern': r'^\s*[}\])].*$'}}
    (a / (language + '-config.json')).write_text(json.dumps(config, indent=2))
for mode in ['dark', 'light']:
    dark = mode == 'dark'
    colors = {'editor.background': '#11141D' if dark else '#FAFBFF', 'editor.foreground': '#D6DCEB' if dark else '#26324A', 'editorLineNumber.foreground': '#4F586D' if dark else '#8992A8', 'editorLineNumber.activeForeground': '#70E1BD' if dark else '#078F6B', 'editor.selectionBackground': '#293D53' if dark else '#D6E5FA', 'editorCursor.foreground': '#70E1BD' if dark else '#078F6B', 'editor.lineHighlightBackground': '#181E2A' if dark else '#EEF2F9', 'editorWhitespace.foreground': '#354054' if dark else '#BCC3D0'}
    token_colors = [
        {'scope': 'comment', 'settings': {'foreground': '#62738D' if dark else '#8793A9', 'fontStyle': 'italic'}},
        {'scope': 'keyword', 'settings': {'foreground': '#BFA3F3' if dark else '#8055BC'}},
        {'scope': 'string', 'settings': {'foreground': '#9ED6A0' if dark else '#328A55'}},
        {'scope': 'constant', 'settings': {'foreground': '#EDB979' if dark else '#B46B28'}},
        {'scope': 'entity.name.function', 'settings': {'foreground': '#7EC7EF' if dark else '#277CAD'}},
        {'scope': 'entity.name.type,entity.name.tag', 'settings': {'foreground': '#73DAD0' if dark else '#178F8C'}},
        {'scope': 'support.type.property-name,entity.other.attribute-name', 'settings': {'foreground': '#BFA3F3' if dark else '#8055BC'}},
        {'scope': 'markup.heading,markup.bold', 'settings': {'foreground': '#70E1BD' if dark else '#078F6B', 'fontStyle': 'bold'}}
    ]
    (a / (mode + '.json')).write_text(json.dumps({'name': 'aster-' + mode, 'type': mode, 'colors': colors, 'tokenColors': token_colors}, indent=2))
print('Generated 18 lightweight TextMate grammars and two editor themes.')