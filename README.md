jparsec-g
=========

Parse Guava TypeToken.

To deserialize TypeToken:

    new TypeParser().parse(typeToken.toString())

To construct TypeToken reflectively:

    TypeToken<?> genericType = parser.parse(theClass.getName() + "<?, String>");

[Javadoc](http://jparsec.github.io/jparsec-g/apidocs/)
