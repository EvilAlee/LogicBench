package io.github.evilalee.gatelab.synth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recursive-descent parser for Boolean expressions.
 *
 * Precedence (highest first): prefix/postfix NOT, AND/NAND, XOR/XNOR, OR/NOR.
 * Binary operators are left-associative.
 */
public final class ExpressionParser {
    private enum Kind {
        IDENTIFIER, ZERO, ONE,
        NOT, AND, NAND, XOR, XNOR, OR, NOR,
        APOSTROPHE, LEFT_PAREN, RIGHT_PAREN, END
    }

    private record Token(Kind kind, String text, int position) {
    }

    private final List<Token> tokens;
    private int cursor;

    private ExpressionParser(String source) {
        this.tokens = tokenize(source);
    }

    public static Expression parse(String source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        ExpressionParser parser = new ExpressionParser(source);
        Expression expression = parser.parseOr();
        Token trailing = parser.peek();
        if (trailing.kind() != Kind.END) {
            throw parser.error(trailing, "unexpected token '" + trailing.text() + "'");
        }
        return expression;
    }

    private Expression parseOr() {
        Expression expression = parseXor();
        while (match(Kind.OR, Kind.NOR)) {
            Kind operator = previous().kind();
            Expression right = parseXor();
            expression = new Expression.Binary(
                    operator == Kind.OR
                            ? Expression.BinaryOperator.OR : Expression.BinaryOperator.NOR,
                    expression, right);
        }
        return expression;
    }

    private Expression parseXor() {
        Expression expression = parseAnd();
        while (match(Kind.XOR, Kind.XNOR)) {
            Kind operator = previous().kind();
            Expression right = parseAnd();
            expression = new Expression.Binary(
                    operator == Kind.XOR
                            ? Expression.BinaryOperator.XOR : Expression.BinaryOperator.XNOR,
                    expression, right);
        }
        return expression;
    }

    private Expression parseAnd() {
        Expression expression = parseUnary();
        while (match(Kind.AND, Kind.NAND)) {
            Kind operator = previous().kind();
            Expression right = parseUnary();
            expression = new Expression.Binary(
                    operator == Kind.AND
                            ? Expression.BinaryOperator.AND : Expression.BinaryOperator.NAND,
                    expression, right);
        }
        return expression;
    }

    private Expression parseUnary() {
        if (match(Kind.NOT)) {
            return new Expression.Unary(Expression.UnaryOperator.NOT, parseUnary());
        }
        Expression expression = parsePostfix();
        while (match(Kind.APOSTROPHE)) {
            expression = new Expression.Unary(Expression.UnaryOperator.NOT, expression);
        }
        return expression;
    }

    private Expression parsePostfix() {
        if (match(Kind.ZERO)) {
            return new Expression.Constant(false);
        }
        if (match(Kind.ONE)) {
            return new Expression.Constant(true);
        }
        if (match(Kind.IDENTIFIER)) {
            return new Expression.Variable(previous().text());
        }
        if (match(Kind.LEFT_PAREN)) {
            Token opening = previous();
            Expression nested = parseOr();
            if (!match(Kind.RIGHT_PAREN)) {
                throw error(peek(), "expected ')' to close '(' at position " + opening.position());
            }
            return nested;
        }
        throw error(peek(), "expected an identifier, constant, or '('");
    }

    private boolean match(Kind... kinds) {
        for (Kind kind : kinds) {
            if (peek().kind() == kind) {
                cursor++;
                return true;
            }
        }
        return false;
    }

    private Token peek() {
        return tokens.get(cursor);
    }

    private Token previous() {
        return tokens.get(cursor - 1);
    }

    private ParseException error(Token token, String message) {
        return new ParseException(token.position(), message);
    }

    private static List<Token> tokenize(String source) {
        List<Token> result = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }

            int start = index;
            switch (current) {
                case '0' -> {
                    result.add(new Token(Kind.ZERO, "0", index++));
                    continue;
                }
                case '1' -> {
                    result.add(new Token(Kind.ONE, "1", index++));
                    continue;
                }
                case '!' , '~' -> {
                    result.add(new Token(Kind.NOT, Character.toString(current), index++));
                    continue;
                }
                case '&', '*' -> {
                    result.add(new Token(Kind.AND, Character.toString(current), index++));
                    continue;
                }
                case '^' -> {
                    result.add(new Token(Kind.XOR, "^", index++));
                    continue;
                }
                case '|', '+' -> {
                    result.add(new Token(Kind.OR, Character.toString(current), index++));
                    continue;
                }
                case '\'' -> {
                    result.add(new Token(Kind.APOSTROPHE, "'", index++));
                    continue;
                }
                case '(' -> {
                    result.add(new Token(Kind.LEFT_PAREN, "(", index++));
                    continue;
                }
                case ')' -> {
                    result.add(new Token(Kind.RIGHT_PAREN, ")", index++));
                    continue;
                }
                default -> {
                }
            }

            if (isAsciiLetter(current)) {
                index++;
                while (index < source.length()
                        && (isAsciiLetter(source.charAt(index))
                            || isAsciiDigit(source.charAt(index))
                            || source.charAt(index) == '_')) {
                    index++;
                }
                String word = source.substring(start, index);
                Kind kind = switch (word.toUpperCase(Locale.ROOT)) {
                    case "NOT" -> Kind.NOT;
                    case "AND" -> Kind.AND;
                    case "NAND" -> Kind.NAND;
                    case "XOR" -> Kind.XOR;
                    case "XNOR" -> Kind.XNOR;
                    case "OR" -> Kind.OR;
                    case "NOR" -> Kind.NOR;
                    default -> Kind.IDENTIFIER;
                };
                result.add(new Token(kind, word, start));
                continue;
            }

            throw new ParseException(index,
                    "unexpected character '" + current + "'");
        }
        result.add(new Token(Kind.END, "", source.length()));
        return List.copyOf(result);
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }
}
