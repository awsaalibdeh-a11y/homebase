"""Homebase's word filter for friend groups.

Rude words, insults and slurs are replaced with asterisks (the first letter stays, so it reads "b****") and reported to the parents. Mild, old-fashioned
cheek ("may your pillow be hot on both sides", "silly goose", "you muppet") is fine and passes untouched. The filter works on whole words, so
"class", "assistant", "Scunthorpe" or "dumbbell" are never caught, and it sees through the usual tricks: f*ck, f u c k, fuuuck, sh1t, b!tch.

moderate(text) -> (text with the bad words starred out, [{"word": ..., "cat": ...}, ...])
categories: "slur" (the worst), "profanity", "insult"
"""
import re

PROFANITY = """fuck fucks fucked fucker fuckers fucking fuckin motherfucker motherfuckers shit shits shitty shite bullshit bitch bitches bitchy bastard bastards asshole assholes
ass asses arse arsehole dumbass jackass dick dicks dickhead cock cocks pussy pussies cunt cunts whore whores slut sluts piss pissed prick pricks twat twats wanker wankers
bollocks douche douchebag""".split()
INSULTS = """dumb stupid stupidly idiot idiots idiotic moron morons moronic imbecile imbeciles loser losers scumbag scumbags dimwit dimwits numbskull""".split()
SLURS = """nigga niggas nigger niggers nigg nga ngga niga nigaa faggot faggots fag fags tranny trannies chink chinks spic spics kike kikes paki gook gooks wetback retard retards
retarded coon coons""".split()

CATEGORY = {w: "slur" for w in SLURS} | {w: "profanity" for w in PROFANITY} | {w: "insult" for w in INSULTS}

LEET = str.maketrans({"0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "@": "a", "$": "s", "!": "i", "|": "i", "+": "t"})
TOKEN = re.compile(r"[A-Za-z0-9@$!|+*]+")


def squash(word):
    """'fuuuck' -> 'fuck': a letter repeated in a row counts once."""
    return re.sub(r"(.)\1+", r"\1", word)


_SQUASHED = {}
for _w, _c in CATEGORY.items():
    _SQUASHED.setdefault(squash(_w), (_w, _c))


def lookup(token):
    """The (word, category) a single token is hiding, or None."""
    t = token.lower()
    if not re.search(r"[a-z]", t.replace("*", "")):                    # numbers and symbols alone ("455") are not letters in disguise
        return None
    forms = {t, t.translate(LEET)}
    for form in list(forms):
        forms.add(squash(form))
    for form in forms:
        stars = "*" in form
        letters = form.replace("*", "")
        if len(letters) < 2:
            continue
        if not stars and form in CATEGORY:
            return form, CATEGORY[form]
        if not stars and form in _SQUASHED and len(form) >= 4:
            return _SQUASHED[form]
        if stars:                                                       # f*ck, b!tch, n***a: the stars stand for any letter
            rx = re.compile("^" + re.escape(form).replace(r"\*", "[a-z]") + "$")
            for w, c in CATEGORY.items():
                if len(w) == len(form) and rx.match(w):
                    return w, c
    # a short word with letters stretched out ("asss", "ngaaa") is the short word
    squashed = squash(t.translate(LEET)) if re.search(r"[a-z]", t) else ""
    if squashed in CATEGORY and len(squashed) <= 3:
        return squashed, CATEGORY[squashed]
    return None


def star(token):
    return token[0] + "*" * (len(token) - 1) if len(token) > 1 else "*"


EDGE = "!$@+|*"


def core_of(match):
    """(start, end) of a token without punctuation stuck to its ends: 'loser!' -> 'loser'. (The '!' is also a leet 'i', so it has to be tried both ways.)"""
    t = match.group()
    a = len(t) - len(t.lstrip(EDGE))
    b = len(t.rstrip(EDGE))
    return (match.start() + a, match.start() + b) if b > a else None


def moderate(text):
    """Returns (text with rude words starred out, flags)."""
    text = str(text or "")
    tokens = list(TOKEN.finditer(text))
    cores = [core_of(m) for m in tokens]
    hits, flags = set(), []                                             # spans to star out
    for m, core in zip(tokens, cores):
        found = lookup(m.group())
        span = m.span()
        if not found and core and core != m.span():
            found = lookup(text[core[0]:core[1]])
            span = core
        if found:
            hits.add(span)
            flags.append({"word": found[0], "cat": found[1]})
    # letters spaced out on purpose: "f u c k", "s.h.i.t"
    i = 0
    while i < len(tokens):
        j = i
        while j < len(tokens) and cores[j] and cores[j][1] - cores[j][0] == 1 and text[cores[j][0]].isalnum() and (j == i or tokens[j].start() - tokens[j - 1].end() <= 2):
            j += 1
        if j - i >= 3:
            joined = "".join(text[c[0]:c[1]] for c in cores[i:j])
            found = lookup(joined)
            if found and not any(c in hits or tokens[k].span() in hits for k, c in zip(range(i, j), cores[i:j])):
                for c in cores[i:j]:
                    hits.add(c)
                flags.append({"word": found[0], "cat": found[1]})
        i = max(j, i + 1)
    out, last = [], 0
    for (a, b) in sorted(hits):
        out.append(text[last:a])
        out.append(star(text[a:b]))
        last = b
    out.append(text[last:])
    seen, unique = set(), []
    for f in flags:
        if (f["word"], f["cat"]) not in seen:
            seen.add((f["word"], f["cat"]))
            unique.append(f)
    return "".join(out), unique


def worst(flags):
    """The most serious category among some flags (for how loudly to tell the parents)."""
    order = {"insult": 1, "profanity": 2, "slur": 3}
    return max((f["cat"] for f in flags), key=lambda c: order[c], default="")
