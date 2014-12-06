jparsec-g
=========

Use jparsec to parse Guava TypeToken.

new TypeParser().parse(serialized_string) and you get a TypeToken deserializer.

It can also be used to flexibly and reflectively construct TypeToken's:

    TypeToken<?> genericType = parser.parse(theClass.getCanonicalName() + "<?, String>");
