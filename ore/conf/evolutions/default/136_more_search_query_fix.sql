# --- !Ups

CREATE OR REPLACE FUNCTION websearch_to_tsquery_postfix(dictionary REGCONFIG, query TEXT) RETURNS TSQUERY
    IMMUTABLE STRICT
    LANGUAGE plpgsql AS
$$
DECLARE
    arr  TEXT[]  := regexp_split_to_array(query, '\s+');;
    last TEXT    := websearch_to_tsquery('simple', arr[array_length(arr, 1)])::TEXT;;
    init TSQUERY := websearch_to_tsquery(dictionary, regexp_replace(query, '\S+$', ''));;
BEGIN
    IF last = '' THEN
        BEGIN
            RETURN init && $2::TSQUERY;;
        EXCEPTION
            WHEN SYNTAX_ERROR THEN
                RETURN init && websearch_to_tsquery('');;
        END;;
    END IF;;

    RETURN init && (websearch_to_tsquery(dictionary, last) || to_tsquery('simple', last || ':*'));;
END;;
$$;

# --- !Downs

CREATE OR REPLACE FUNCTION websearch_to_tsquery_postfix(dictionary REGCONFIG, query TEXT) RETURNS TSQUERY
    IMMUTABLE STRICT
    LANGUAGE plpgsql AS
$$
DECLARE
    arr  TEXT[]  := regexp_split_to_array(query, '\s+');;
    last TEXT    := websearch_to_tsquery('simple', arr[array_length(arr, 1)])::TEXT;;
    init TSQUERY := websearch_to_tsquery(dictionary, regexp_replace(query, '\S+$', ''));;
BEGIN
    IF last = '' THEN
        BEGIN
            RETURN init && $2::TSQUERY;;
        EXCEPTION
            WHEN SYNTAX_ERROR THEN
                RETURN init && websearch_to_tsquery('');;
        END;;
    END IF;;

    RETURN init && to_tsquery('simple', last || ':*');;
END;;
$$;
