--
-- PostgreSQL database dump
--

-- Dumped from database version 16.2 (Debian 16.2-1.pgdg120+2)
-- Dumped by pg_dump version 16.2 (Debian 16.2-1.pgdg120+2)

-- SET statement_timeout = 0;
-- SET lock_timeout = 0;
-- SET idle_in_transaction_session_timeout = 0;
-- SET client_encoding = 'UTF8';
-- SET standard_conforming_strings = on;
-- SELECT pg_catalog.set_config('search_path', '', false);
-- SET check_function_bodies = false;
-- SET xmloption = content;
-- SET client_min_messages = warning;
-- SET row_security = off;

--
-- Name: AvatarType; Type: TYPE; Schema: public; Owner: username
--

CREATE TYPE public."AvatarType" AS ENUM (
    'default',
    'predefined',
    'upload'
    );


ALTER TYPE public."AvatarType" OWNER TO username;

--
-- Name: UserRegisterLogType; Type: TYPE; Schema: public; Owner: username
--

CREATE TYPE public."UserRegisterLogType" AS ENUM (
    'RequestSuccess',
    'RequestFailDueToAlreadyRegistered',
    'RequestFailDueToInvalidOrNotSupportedEmail',
    'RequestFailDurToSecurity',
    'RequestFailDueToSendEmailFailure',
    'Success',
    'FailDueToUserExistence',
    'FailDueToWrongCodeOrExpired'
    );


ALTER TYPE public."UserRegisterLogType" OWNER TO username;

--
-- Name: UserResetPasswordLogType; Type: TYPE; Schema: public; Owner: username
--

CREATE TYPE public."UserResetPasswordLogType" AS ENUM (
    'RequestSuccess',
    'RequestFailDueToNoneExistentEmail',
    'RequestFailDueToSecurity',
    'Success',
    'FailDueToInvalidToken',
    'FailDueToExpiredRequest',
    'FailDueToNoUser'
    );


ALTER TYPE public."UserResetPasswordLogType" OWNER TO username;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: avatar; Type: TABLE; Schema: public; Owner: username
--

CREATE TABLE public.avatar
(
    id          integer                                               NOT NULL,
    url         character varying                                     NOT NULL,
    name        character varying                                     NOT NULL,
    created_at  timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    avatar_type public."AvatarType"                                   NOT NULL,
    usage_count integer                     DEFAULT 0                 NOT NULL
);


ALTER TABLE public.avatar
    OWNER TO username;

--
-- Name: avatar_id_seq; Type: SEQUENCE; Schema: public; Owner: username
--

CREATE SEQUENCE public.avatar_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.avatar_id_seq OWNER TO username;

--
-- Name: avatar_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: username
--

ALTER SEQUENCE public.avatar_id_seq OWNED BY public.avatar.id;


--
-- Name: session; Type: TABLE; Schema: public; Owner: username
--

CREATE TABLE public.session
(
    id                integer                                               NOT NULL,
    valid_until       timestamp(6) with time zone                           NOT NULL,
    revoked           boolean                                               NOT NULL,
    user_id           integer                                               NOT NULL,
    "authorization"   text                                                  NOT NULL,
    last_refreshed_at bigint                                                NOT NULL,
    created_at        timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


ALTER TABLE public.session
    OWNER TO username;

--
-- Name: session_id_seq; Type: SEQUENCE; Schema: public; Owner: username
--

CREATE SEQUENCE public.session_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.session_id_seq OWNER TO username;

--
-- Name: session_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: username
--

ALTER SEQUENCE public.session_id_seq OWNED BY public.session.id;


--
-- Name: session_refresh_log; Type: TABLE; Schema: public; Owner: username
--

CREATE TABLE public.session_refresh_log
(
    id                integer                                               NOT NULL,
    session_id        integer                                               NOT NULL,
    old_refresh_token text                                                  NOT NULL,
    new_refresh_token text                                                  NOT NULL,
    access_token      text                                                  NOT NULL,
    created_at        timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


ALTER TABLE public.session_refresh_log
    OWNER TO username;

--
-- Name: session_refresh_log_id_seq; Type: SEQUENCE; Schema: public; Owner: username
--

CREATE SEQUENCE public.session_refresh_log_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.session_refresh_log_id_seq OWNER TO username;

--
-- Name: session_refresh_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: username
--

ALTER SEQUENCE public.session_refresh_log_id_seq OWNED BY public.session_refresh_log.id;


--
-- Name: user; Type: TABLE; Schema: public; Owner: username
--

CREATE TABLE public."user"
(
    id              integer                                               NOT NULL,
    username        character varying                                     NOT NULL,
    hashed_password character varying                                     NOT NULL,
    email           character varying                                     NOT NULL,
    created_at      timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at      timestamp(6) with time zone
);


ALTER TABLE public."user"
    OWNER TO username;

--
-- Name: user_following_relationship; Type: TABLE; Schema: public; Owner: username
--

CREATE TABLE public.user_following_relationship
(
    id          integer                                               NOT NULL,
    followee_id integer                                               NOT NULL,
    follower_id integer                                               NOT NULL,
    created_at  timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at  timestamp(6) with time zone
);


ALTER TABLE public.user_following_relationship
    OWNER TO username;

--
-- Name: user_following_relationship_id_seq; Type: SEQUENCE; Schema: public; Owner: username
--

CREATE SEQUENCE public.user_following_relationship_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.user_following_relationship_id_seq OWNER TO username;

--
-- Name: user_following_relationship_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: username
--

ALTER SEQUENCE public.user_following_relationship_id_seq OWNED BY public.user_following_relationship.id;


--
-- Name: user_id_seq; Type: SEQUENCE; Schema: public; Owner: username
--

CREATE SEQUENCE public.user_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.user_id_seq OWNER TO username;

--
-- Name: user_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: username
--

ALTER SEQUENCE public.user_id_seq OWNED BY public."user".id;


--
-- Name: user_login_log; Type: TABLE; Schema: public; Owner: username
--

CREATE TABLE public.user_login_log
(
    id         integer                                               NOT NULL,
    user_id    integer                                               NOT NULL,
    ip         character varying                                     NOT NULL,
    user_agent character varying,
    created_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


ALTER TABLE public.user_login_log
    OWNER TO username;

--
-- Name: user_login_log_id_seq; Type: SEQUENCE; Schema: public; Owner: username
--

CREATE SEQUENCE public.user_login_log_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.user_login_log_id_seq OWNER TO username;

--
-- Name: user_login_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: username
--

ALTER SEQUENCE public.user_login_log_id_seq OWNED BY public.user_login_log.id;


--
-- Name: user_profile; Type: TABLE; Schema: public; Owner: username
--

CREATE TABLE public.user_profile
(
    id         integer                                               NOT NULL,
    user_id    integer                                               NOT NULL,
    nickname   character varying                                     NOT NULL,
    avatar_id  integer                                               NOT NULL,
    intro      character varying                                     NOT NULL,
    created_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamp(6) with time zone
);


ALTER TABLE public.user_profile
    OWNER TO username;

--
-- Name: user_profile_id_seq; Type: SEQUENCE; Schema: public; Owner: username
--

CREATE SEQUENCE public.user_profile_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.user_profile_id_seq OWNER TO username;

--
-- Name: user_profile_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: username
--

ALTER SEQUENCE public.user_profile_id_seq OWNED BY public.user_profile.id;


--
-- Name: user_profile_query_log; Type: TABLE; Schema: public; Owner: username
--

CREATE TABLE public.user_profile_query_log
(
    id         integer                                               NOT NULL,
    viewer_id  integer,
    viewee_id  integer                                               NOT NULL,
    ip         character varying                                     NOT NULL,
    user_agent character varying,
    created_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


ALTER TABLE public.user_profile_query_log
    OWNER TO username;

--
-- Name: user_profile_query_log_id_seq; Type: SEQUENCE; Schema: public; Owner: username
--

CREATE SEQUENCE public.user_profile_query_log_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.user_profile_query_log_id_seq OWNER TO username;

--
-- Name: user_profile_query_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: username
--

ALTER SEQUENCE public.user_profile_query_log_id_seq OWNED BY public.user_profile_query_log.id;


--
-- Name: user_register_log; Type: TABLE; Schema: public; Owner: username
--

CREATE TABLE public.user_register_log
(
    id         integer                                               NOT NULL,
    email      character varying                                     NOT NULL,
    type       public."UserRegisterLogType"                          NOT NULL,
    ip         character varying                                     NOT NULL,
    user_agent character varying,
    created_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


ALTER TABLE public.user_register_log
    OWNER TO username;

--
-- Name: user_register_log_id_seq; Type: SEQUENCE; Schema: public; Owner: username
--

CREATE SEQUENCE public.user_register_log_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.user_register_log_id_seq OWNER TO username;

--
-- Name: user_register_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: username
--

ALTER SEQUENCE public.user_register_log_id_seq OWNED BY public.user_register_log.id;


--
-- Name: user_register_request; Type: TABLE; Schema: public; Owner: username
--

CREATE TABLE public.user_register_request
(
    id         integer                                               NOT NULL,
    email      character varying                                     NOT NULL,
    code       character varying                                     NOT NULL,
    created_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


ALTER TABLE public.user_register_request
    OWNER TO username;

--
-- Name: user_register_request_id_seq; Type: SEQUENCE; Schema: public; Owner: username
--

CREATE SEQUENCE public.user_register_request_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.user_register_request_id_seq OWNER TO username;

--
-- Name: user_register_request_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: username
--

ALTER SEQUENCE public.user_register_request_id_seq OWNED BY public.user_register_request.id;


--
-- Name: user_reset_password_log; Type: TABLE; Schema: public; Owner: username
--

CREATE TABLE public.user_reset_password_log
(
    id         integer                                               NOT NULL,
    user_id    integer,
    type       public."UserResetPasswordLogType"                     NOT NULL,
    ip         character varying                                     NOT NULL,
    user_agent character varying,
    created_at timestamp(6) with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


ALTER TABLE public.user_reset_password_log
    OWNER TO username;

--
-- Name: user_reset_password_log_id_seq; Type: SEQUENCE; Schema: public; Owner: username
--

CREATE SEQUENCE public.user_reset_password_log_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.user_reset_password_log_id_seq OWNER TO username;

--
-- Name: user_reset_password_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: username
--

ALTER SEQUENCE public.user_reset_password_log_id_seq OWNED BY public.user_reset_password_log.id;


--
-- Name: avatar id; Type: DEFAULT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.avatar
    ALTER COLUMN id SET DEFAULT nextval('public.avatar_id_seq'::regclass);


--
-- Name: session id; Type: DEFAULT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.session
    ALTER COLUMN id SET DEFAULT nextval('public.session_id_seq'::regclass);


--
-- Name: session_refresh_log id; Type: DEFAULT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.session_refresh_log
    ALTER COLUMN id SET DEFAULT nextval('public.session_refresh_log_id_seq'::regclass);


--
-- Name: user id; Type: DEFAULT; Schema: public; Owner: username
--

ALTER TABLE ONLY public."user"
    ALTER COLUMN id SET DEFAULT nextval('public.user_id_seq'::regclass);


--
-- Name: user_following_relationship id; Type: DEFAULT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_following_relationship
    ALTER COLUMN id SET DEFAULT nextval('public.user_following_relationship_id_seq'::regclass);


--
-- Name: user_login_log id; Type: DEFAULT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_login_log
    ALTER COLUMN id SET DEFAULT nextval('public.user_login_log_id_seq'::regclass);


--
-- Name: user_profile id; Type: DEFAULT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_profile
    ALTER COLUMN id SET DEFAULT nextval('public.user_profile_id_seq'::regclass);


--
-- Name: user_profile_query_log id; Type: DEFAULT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_profile_query_log
    ALTER COLUMN id SET DEFAULT nextval('public.user_profile_query_log_id_seq'::regclass);


--
-- Name: user_register_log id; Type: DEFAULT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_register_log
    ALTER COLUMN id SET DEFAULT nextval('public.user_register_log_id_seq'::regclass);


--
-- Name: user_register_request id; Type: DEFAULT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_register_request
    ALTER COLUMN id SET DEFAULT nextval('public.user_register_request_id_seq'::regclass);


--
-- Name: user_reset_password_log id; Type: DEFAULT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_reset_password_log
    ALTER COLUMN id SET DEFAULT nextval('public.user_reset_password_log_id_seq'::regclass);


--
-- Name: user_register_log PK_3596a6f74bd2a80be930f6d1e39; Type: CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_register_log
    ADD CONSTRAINT "PK_3596a6f74bd2a80be930f6d1e39" PRIMARY KEY (id);


--
-- Name: user_following_relationship PK_3b0199015f8814633fc710ff09d; Type: CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_following_relationship
    ADD CONSTRAINT "PK_3b0199015f8814633fc710ff09d" PRIMARY KEY (id);


--
-- Name: user_reset_password_log PK_3ee4f25e7f4f1d5a9bd9817b62b; Type: CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_reset_password_log
    ADD CONSTRAINT "PK_3ee4f25e7f4f1d5a9bd9817b62b" PRIMARY KEY (id);


--
-- Name: user_profile_query_log PK_9aeff7c959703fad866e9ad581a; Type: CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_profile_query_log
    ADD CONSTRAINT "PK_9aeff7c959703fad866e9ad581a" PRIMARY KEY (id);


--
-- Name: user PK_cace4a159ff9f2512dd42373760; Type: CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT "PK_cace4a159ff9f2512dd42373760" PRIMARY KEY (id);


--
-- Name: user_register_request PK_cdf2d880551e43d9362ddd37ae0; Type: CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_register_request
    ADD CONSTRAINT "PK_cdf2d880551e43d9362ddd37ae0" PRIMARY KEY (id);


--
-- Name: user_profile PK_f44d0cd18cfd80b0fed7806c3b7; Type: CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_profile
    ADD CONSTRAINT "PK_f44d0cd18cfd80b0fed7806c3b7" PRIMARY KEY (id);


--
-- Name: session PK_f55da76ac1c3ac420f444d2ff11; Type: CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.session
    ADD CONSTRAINT "PK_f55da76ac1c3ac420f444d2ff11" PRIMARY KEY (id);


--
-- Name: user_login_log PK_f8db79b1af1f385db4f45a2222e; Type: CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_login_log
    ADD CONSTRAINT "PK_f8db79b1af1f385db4f45a2222e" PRIMARY KEY (id);


--
-- Name: session_refresh_log PK_f8f46c039b0955a7df6ad6631d7; Type: CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.session_refresh_log
    ADD CONSTRAINT "PK_f8f46c039b0955a7df6ad6631d7" PRIMARY KEY (id);


--
-- Name: avatar avatar_pkey; Type: CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.avatar
    ADD CONSTRAINT avatar_pkey PRIMARY KEY (id);


--
-- Name: IDX_1261db28434fde159acda6094b; Type: INDEX; Schema: public; Owner: username
--

CREATE INDEX "IDX_1261db28434fde159acda6094b" ON public.user_profile_query_log USING btree (viewer_id);


--
-- Name: IDX_3af79f07534d9f1c945cd4c702; Type: INDEX; Schema: public; Owner: username
--

CREATE INDEX "IDX_3af79f07534d9f1c945cd4c702" ON public.user_register_log USING btree (email);


--
-- Name: IDX_3d2f174ef04fb312fdebd0ddc5; Type: INDEX; Schema: public; Owner: username
--

CREATE INDEX "IDX_3d2f174ef04fb312fdebd0ddc5" ON public.session USING btree (user_id);


--
-- Name: IDX_51cb79b5555effaf7d69ba1cff; Type: INDEX; Schema: public; Owner: username
--

CREATE UNIQUE INDEX "IDX_51cb79b5555effaf7d69ba1cff" ON public.user_profile USING btree (user_id);


--
-- Name: IDX_66c592c7f7f20d1214aba2d004; Type: INDEX; Schema: public; Owner: username
--

CREATE INDEX "IDX_66c592c7f7f20d1214aba2d004" ON public.user_login_log USING btree (user_id);


--
-- Name: IDX_78a916df40e02a9deb1c4b75ed; Type: INDEX; Schema: public; Owner: username
--

CREATE UNIQUE INDEX "IDX_78a916df40e02a9deb1c4b75ed" ON public."user" USING btree (username);


--
-- Name: IDX_868df0c2c3a138ee54d2a515bc; Type: INDEX; Schema: public; Owner: username
--

CREATE INDEX "IDX_868df0c2c3a138ee54d2a515bc" ON public.user_following_relationship USING btree (follower_id);


--
-- Name: IDX_bb46e87d5b3f1e55c625755c00; Type: INDEX; Schema: public; Owner: username
--

CREATE INDEX "IDX_bb46e87d5b3f1e55c625755c00" ON public.session USING btree (valid_until);


--
-- Name: IDX_c1d0ecc369d7a6a3d7e876c589; Type: INDEX; Schema: public; Owner: username
--

CREATE INDEX "IDX_c1d0ecc369d7a6a3d7e876c589" ON public.user_register_request USING btree (email);


--
-- Name: IDX_c78831eeee179237b1482d0c6f; Type: INDEX; Schema: public; Owner: username
--

CREATE INDEX "IDX_c78831eeee179237b1482d0c6f" ON public.user_following_relationship USING btree (followee_id);


--
-- Name: IDX_e12875dfb3b1d92d7d7c5377e2; Type: INDEX; Schema: public; Owner: username
--

CREATE UNIQUE INDEX "IDX_e12875dfb3b1d92d7d7c5377e2" ON public."user" USING btree (email);


--
-- Name: IDX_ff592e4403b328be0de4f2b397; Type: INDEX; Schema: public; Owner: username
--

CREATE INDEX "IDX_ff592e4403b328be0de4f2b397" ON public.user_profile_query_log USING btree (viewee_id);


--
-- Name: user_profile_query_log FK_1261db28434fde159acda6094bc; Type: FK CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_profile_query_log
    ADD CONSTRAINT "FK_1261db28434fde159acda6094bc" FOREIGN KEY (viewer_id) REFERENCES public."user" (id);


--
-- Name: user_login_log FK_66c592c7f7f20d1214aba2d0046; Type: FK CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_login_log
    ADD CONSTRAINT "FK_66c592c7f7f20d1214aba2d0046" FOREIGN KEY (user_id) REFERENCES public."user" (id);


--
-- Name: user_following_relationship FK_868df0c2c3a138ee54d2a515bce; Type: FK CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_following_relationship
    ADD CONSTRAINT "FK_868df0c2c3a138ee54d2a515bce" FOREIGN KEY (follower_id) REFERENCES public."user" (id);


--
-- Name: user_following_relationship FK_c78831eeee179237b1482d0c6fb; Type: FK CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_following_relationship
    ADD CONSTRAINT "FK_c78831eeee179237b1482d0c6fb" FOREIGN KEY (followee_id) REFERENCES public."user" (id);


--
-- Name: user_profile_query_log FK_ff592e4403b328be0de4f2b3973; Type: FK CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_profile_query_log
    ADD CONSTRAINT "FK_ff592e4403b328be0de4f2b3973" FOREIGN KEY (viewee_id) REFERENCES public."user" (id);


--
-- Name: user_profile fk_user_profile_avatar_id; Type: FK CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_profile
    ADD CONSTRAINT fk_user_profile_avatar_id FOREIGN KEY (avatar_id) REFERENCES public.avatar (id);


--
-- Name: user_profile fk_user_profile_user_id; Type: FK CONSTRAINT; Schema: public; Owner: username
--

ALTER TABLE ONLY public.user_profile
    ADD CONSTRAINT fk_user_profile_user_id FOREIGN KEY (user_id) REFERENCES public."user" (id);


--
-- PostgreSQL database dump complete
--

