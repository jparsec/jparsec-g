jparsec-g
=========

Use jparsec to parse Guava TypeToken.

To deserialize TypeToken:

    new TypeParser().parse(serialized_string)

To flexibly and reflectively construct TypeToken:

    TypeToken<?> genericType = parser.parse(theClass.getCanonicalName() + "<?, String>");

[Javadoc](http://jparsec.github.io/jparsec-g/apidocs/)
