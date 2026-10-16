import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from moderation import moderate, worst  # noqa: E402


class Filter(unittest.TestCase):
    def flagged(self, text):
        return [f["word"] for f in moderate(text)[1]]

    def test_the_words_you_named_are_starred_out_and_reported(self):
        self.assertEqual(moderate("you are so dumb")[0], "you are so d***")
        self.assertEqual(moderate("what a stupid idea")[0], "what a s***** idea")
        self.assertEqual(moderate("bitch please")[0], "b**** please")
        self.assertEqual(moderate("fuck this")[0], "f*** this")
        self.assertEqual(self.flagged("dumb stupid bitch fuck"), ["dumb", "stupid", "bitch", "fuck"])

    def test_categories(self):
        cats = [f["cat"] for f in moderate("stupid fuck nigga retard")[1]]
        self.assertEqual(cats, ["insult", "profanity", "slur", "slur"])
        self.assertEqual(worst(moderate("dumb")[1]), "insult")
        self.assertEqual(worst(moderate("dumb fuck")[1]), "profanity")
        self.assertEqual(worst(moderate("dumb fuck nga")[1]), "slur")
        self.assertEqual(worst([]), "")

    def test_the_short_slur_you_named_is_caught_even_stretched(self):
        for text in ("nga", "NGA", "ngaaa", "n*ga", "n!gga", "ngga"):
            self.assertEqual(len(moderate(text)[1]), 1, text)

    def test_mild_cheek_is_allowed_untouched(self):
        for ok in ("may your pillow be hot on both sides", "you silly goose", "oh you muppet", "that is a bit naughty", "what the heck", "darn it", "oh my gosh",
                   "you cheeky monkey", "hell no", "damn that was close", "crap, I forgot my bag", "shut the front door", "you absolute walnut"):
            self.assertEqual(moderate(ok), (ok, []), ok)

    def test_ordinary_words_that_merely_contain_a_bad_one_are_never_caught(self):
        for ok in ("class assistant", "Scunthorpe", "dumbbell workout", "I will pass the mass", "cocktail party", "grape juice", "assignment due", "classic", "bassist",
                   "analysis", "the title of the book", "shitake mushrooms are lovely".replace("shitake", "shiitake"), "Essex", "Cockburn street", "pass the salt"):
            self.assertEqual(self.flagged(ok), [], ok)

    def test_numbers_are_not_letters_in_disguise(self):
        for ok in ("score is 455", "room 5 5", "I paid 100", "call 1234", "55555"):
            self.assertEqual(self.flagged(ok), [], ok)

    def test_the_usual_tricks_are_seen_through(self):
        for trick in ("f*ck", "f**k", "fuuuuck", "sh1t", "b!tch", "b1tch", "FUCK", "Fuck", "f.u.c.k", "f u c k", "s h i t", "$hit", "stup1d", "stuuupid", "d u m b", "fuk".replace("fuk", "fuck")):
            self.assertTrue(moderate(trick)[1], trick)

    def test_only_the_bad_word_is_starred_the_rest_is_kept_exactly(self):
        self.assertEqual(moderate("Hey, you STUPID loser! See you at 5.")[0], "Hey, you S***** l**** ! See you at 5.".replace("l**** !", "l****!"))
        self.assertEqual(moderate("")[0], "")
        self.assertEqual(moderate(None), ("", []))
        self.assertEqual(moderate("hello 😀 world, bitch")[0], "hello 😀 world, b****")

    def test_the_same_word_twice_is_reported_once(self):
        text, flags = moderate("dumb dumb dumb")
        self.assertEqual(text, "d*** d*** d***")
        self.assertEqual(flags, [{"word": "dumb", "cat": "insult"}])

    def test_inflections(self):
        for w in ("fucking", "fucked", "fucker", "motherfucker", "bullshit", "shitty", "assholes", "bitches", "idiots", "morons", "losers", "dumbass"):
            self.assertTrue(moderate(w)[1], w)


if __name__ == "__main__":
    unittest.main()
