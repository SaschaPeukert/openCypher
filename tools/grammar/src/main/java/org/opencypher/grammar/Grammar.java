package org.opencypher.grammar;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.ParserConfigurationException;

import org.opencypher.tools.xml.XmlParser;
import org.xml.sax.SAXException;

import static java.util.Objects.requireNonNull;

public interface Grammar
{
    String XML_NAMESPACE = "http://thobe.org/grammar";
    String SCOPE_XML_NAMESPACE = "http://thobe.org/scope";

    static Grammar parseXML( Path input, ParserOption... options )
            throws ParserConfigurationException, SAXException, IOException
    {
        return Root.XML.parse( input, ParserOption.xml( options ) )
                       .resolve( ParserOption.resolve( options ) );
    }

    static Grammar parseXML( InputStream input, ParserOption... options )
            throws ParserConfigurationException, SAXException, IOException
    {
        return Root.XML.parse( input, ParserOption.xml( options ) )
                       .resolve( ParserOption.resolve( options ) );
    }

    String language();

    boolean caseSensitiveByDefault();

    <EX extends Exception> void accept( GrammarVisitor<EX> visitor ) throws EX;

    boolean hasProduction( String name );

    <P, R, EX extends Exception> R transform( String production, ProductionTransformation<P, R, EX> xform, P param )
            throws EX;

    static Builder grammar( String language, Option... options )
    {
        Builder builder = new Builder( language );
        if ( options != null )
        {
            for ( Option option : options )
            {
                option.apply( builder );
            }
        }
        return builder;
    }

    static Term epsilon()
    {
        return Node.epsilon();
    }

    static Term literal( String value )
    {
        LiteralNode literal = new LiteralNode();
        literal.value = requireNonNull( value, "literal value" );
        return literal;
    }

    final class CharacterSet extends CharacterSetNode
    {
        private CharacterSet( String name )
        {
            this.set = name;
        }

        public CharacterSet except( int... codePoints )
        {
            for ( int codePoint : codePoints )
            {
                exclude( codePoint );
            }
            return this;
        }

        public CharacterSet except( String... literals )
        {
            for ( String literal : literals )
            {
                exclude( literal );
            }
            return this;
        }
    }

    static CharacterSet charactersOfSet( String name )
    {
        return new CharacterSet( requireNonNull( name, "character set name" ) );
    }

    static CharacterSet anyCharacter()
    {
        return new CharacterSet( CharacterSetNode.DEFAULT_SET );
    }

    static Term nonTerminal( String production )
    {
        NonTerminalNode nonTerminal = new NonTerminalNode();
        nonTerminal.ref = production;
        return nonTerminal;
    }

    static Term optional( Term first, Term... more )
    {
        return sequence( first, more ).addTo( new OptionalNode() );
    }

    static Term oneOf( Term first, Term... alternatives )
    {
        if ( alternatives == null || alternatives.length == 0 )
        {
            return first;
        }
        return new AlternativesNode().addAll( first, alternatives );
    }

    static Term zeroOrMore( Term first, Term... more )
    {
        return sequence( first, more ).addTo( new RepetitionNode() );
    }

    static Term oneOrMore( Term first, Term... more )
    {
        return atLeast( 1, first, more );
    }

    static Term atLeast( int times, Term first, Term... more )
    {
        RepetitionNode repetition = new RepetitionNode();
        repetition.min = times;
        return sequence( first, more ).addTo( repetition );
    }

    static Term repeat( int times, Term first, Term... more )
    {
        RepetitionNode repetition = new RepetitionNode();
        repetition.min = repetition.max = times;
        return sequence( first, more ).addTo( repetition );
    }

    static Term repeat( int min, int max, Term first, Term... more )
    {
        RepetitionNode repetition = new RepetitionNode();
        repetition.min = min;
        repetition.max = max;
        return sequence( first, more ).addTo( repetition );
    }

    static Term sequence( Term first, Term... more )
    {
        if ( more == null || more.length == 0 )
        {
            return first;
        }
        return new SequenceNode().addAll( first, more );
    }

    class Builder extends Root
    {
        public enum Option
        {
            IGNORE_UNUSED_PRODUCTIONS( Root.ResolutionOption.IGNORE_UNUSED_PRODUCTIONS );
            private final Root.ResolutionOption option;

            Option( ResolutionOption option )
            {
                this.option = option;
            }
        }

        private Builder( String language )
        {
            this.language = requireNonNull( language, "language name" );
        }

        public Builder production( String name, Term first, Term... alternatives )
        {
            ProductionNode production = new ProductionNode( this );
            production.name = requireNonNull( name, "name" );
            Grammar.oneOf( first, alternatives ).addTo( production );
            add( production );
            return this;
        }

        public Grammar build( Option... options )
        {
            return resolve( (options == null ? Stream.<Option>empty() : Stream.of( options ))
                                    .map( x -> x.option )
                                    .toArray( ResolutionOption[]::new ) );
        }
    }

    abstract class Term
    {
        public final <EX extends Exception> void accept( GrammarVisitor<EX> visitor ) throws EX
        {
            transform( Node.visit(), visitor );
        }

        public abstract <P, T, EX extends Exception> T transform( TermTransformation<P, T, EX> transformation, P param )
                throws EX;

        abstract Container addTo( Container container );

        abstract Sequenced addTo( Sequenced sequenced );

        abstract ProductionNode addTo( ProductionNode production );
    }

    abstract class Option
    {
        abstract void apply( Root grammar );
    }

    Option CASE_INSENSITIVE = new Option()
    {
        @Override
        void apply( Root grammar )
        {
            grammar.caseSensitive = false;
        }
    };

    enum ParserOption
    {
        FAIL_ON_UNKNOWN_XML_ATTRIBUTE( XmlParser.Option.FAIL_ON_UNKNOWN_ATTRIBUTE ),
        SKIP_UNUSED_PRODUCTIONS( Root.ResolutionOption.SKIP_UNUSED_PRODUCTIONS ),
        ALLOW_ROOTLESS_GRAMMAR( Root.ResolutionOption.ALLOW_ROOTLESS );

        private final Object option;

        ParserOption( Root.ResolutionOption option )
        {
            this.option = option;
        }

        ParserOption( XmlParser.Option option )
        {
            this.option = option;
        }

        public static ParserOption[] from( Properties properties )
        {
            Set<ParserOption> result = EnumSet.noneOf( ParserOption.class );
            for ( ParserOption option : values() )
            {
                if ( Boolean.parseBoolean( properties.getProperty( option.name() ) ) )
                {
                    result.add( option );
                }
            }
            return result.toArray( new ParserOption[result.size()] );
        }

        private static XmlParser.Option[] xml( ParserOption[] options )
        {
            return options( XmlParser.Option.class, options );
        }

        private static Root.ResolutionOption[] resolve( ParserOption[] options )
        {
            return options( Root.ResolutionOption.class, options );
        }

        private static <T> T[] options( Class<T> type, ParserOption... options )
        {
            if ( options == null || options.length == 0 )
            {
                return null;
            }
            List<T> collected = Stream.of( options )
                                      .flatMap( ( the ) -> type.isInstance( the.option )
                                                           ? Stream.of( type.cast( the.option ) )
                                                           : Stream.empty() )
                                      .collect( Collectors.<T>toList() );
            @SuppressWarnings("unchecked")
            T[] result = (T[]) Array.newInstance( type, collected.size() );
            return collected.toArray( result );
        }
    }
}
