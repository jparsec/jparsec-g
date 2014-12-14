jparsec-g
=========

Parse Guava TypeToken.

To deserialize TypeToken:

    new TypeParser().parse(typeToken.toString())

To construct TypeToken reflectively:

    TypeToken<?> genericType = parser.parse(theClass.getName() + "<?, String>");

## [Javadoc](http://jparsec.github.io/jparsec-g/apidocs/)

## Maven

Add the following fragment to your `<dependencies>` section:

      <dependency>
        <groupId>org.jparsec</groupId>
        <artifactId>jparsec-g</artifactId>
        <version>1.1</version>
      </dependency>
