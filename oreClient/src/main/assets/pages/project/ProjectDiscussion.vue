<template>
  <div>
    <div v-if="discourseData" class="row posts">
      <div class="col-xs-12">
        <header class="discourse" data-embed-state="loaded">
          <span class="replies">{{ discourseData.posts_count }} replies</span>
        </header>
        <div v-for="post in postsFiltered" :key="post.id">
          <article :id="'post-' + post.id" class="post">
            <a
              :href="config.discourse.baseUrl + '/t/' + post.topic_slug + '/' + post.topic_id + '/' + post.post_number"
              :title="post.created_at"
              class="post-date"
              target="_blank"
              >{{ formatDate(post.created_at) }}</a
            >
            <div class="author">
              <a :href="config.discourse.baseUrl + '/u/' + post.username" target="_blank">
                <img :src="config.discourse.baseUrl + post.avatar_template.replace('{size}', '45')" alt="" />
              </a>
            </div>
            <div class="cooked">
              <h3 class="username">
                <a :href="config.discourse.baseUrl + '/u/' + post.username" class="" target="_blank">{{
                  post.username
                }}</a>
              </h3>
              <!-- eslint-disable-next-line vue/no-v-html -->
              <span v-html="post.cooked" />
            </div>
          </article>
        </div>
      </div>
      <div class="col-xs-12">
        <a class="btn yellow mt-2" :href="config.discourse.baseUrl + '/t/' + 23">Reply on the forums</a>
      </div>
    </div>
  </div>
</template>

<script>
import { mapState } from 'vuex'
import NProgress from 'nprogress'
import config from '../../config.json5'
import { genericError } from '../../utils'

export default {
  data() {
    return {
      discourseData: null,
    }
  },
  computed: {
    routes() {
      return jsRoutes.controllers
    },
    postsFiltered() {
      return this.discourseData.post_stream.posts.filter(
        (p) => p.post_number !== 1 && p.hidden === false && p.deleted_at === null
      )
    },
    ...mapState('project', ['project']),
    config() {
      return config
    },
  },
  watch: {
    project: {
      handler(val, oldVal) {
        // eslint-disable-next-line camelcase
        if (val && val.plugin_id !== oldVal?.plugin_id) {
          this.updateData(val.external.discourse.topic_id)
        }
      },
      immediate: true,
    },
  },
  methods: {
    path() {
      return document.location.pathname
    },
    formatDate(date) {
      return new Date(date).toLocaleDateString('default', { year: 'numeric', month: 'long', day: 'numeric' })
    },
    updateData(topicId) {
      NProgress.start()
      $.ajax({
        url: config.discourse.baseUrl + '/t/' + topicId + '.json',
        method: 'GET',
        contentType: 'application/json',
        crossDomain: true,
      })
        .always(() => {
          NProgress.done()
        })
        .done((data) => {
          this.discourseData = data
        })
        .fail((xhr) => {
          genericError(this, 'An error occoured when loading discussions')
        })
    },
  },
}
</script>
<style lang="scss">
.posts {
  .meta {
    display: none;
  }
  font-size: 15px;

  .username {
    font-weight: bold;
  }
  h1,
  h2,
  h3,
  h4,
  h5 {
    display: block;
    margin-inline-start: 0;
    margin-inline-end: 0;
    font-weight: bold;
  }
  h1 {
    font-size: 1.7511em;
    margin: 0.67em 0;
  }
  h2 {
    font-size: 1.5em;
    margin-block-start: 0.83em;
    margin-block-end: 0.83em;
  }
  h3:not(.username) {
    font-size: 1.17em;
    margin-block-start: 1em;
    margin-block-end: 1em;
  }
  h4 {
    margin-block-start: 1.33em;
    margin-block-end: 1.33em;
  }
  h5 {
    font-size: 0.83em;
    margin-block-start: 1.67em;
    margin-block-end: 1.67em;
  }
  .logo,
  footer,
  header > a {
    display: none;
  }
  a:not(.btn) {
    color: #08c;
    text-decoration: none;
    cursor: pointer;
    &:visited,
    &:hover,
    &:active {
      color: #22527b;
    }
  }
  hr {
    display: block;
    height: 1px;
    margin: 1em 0;
    border: 0;
    border-top: 1px solid #e9e9e9;
    padding: 0;
  }
  ul,
  dd {
    margin: 0 0 9px 25px;
    padding: 0;
  }
  .cooked ul,
  .cooked ol,
  .cooked dd {
    clear: both;
  }
  .cooked ul,
  .d-editor-preview ul {
    margin: 0;
    padding-left: 40px;
  }
  li > ul,
  li > ol {
    margin-bottom: 0;
  }
  img {
    vertical-align: middle;
  }
  fieldset {
    margin: 0;
    border: 0;
    padding: 0;
  }
  pre code {
    overflow: auto;
    tab-size: 4;
  }
  tbody {
    border-top: 3px solid #e9e9e9;
  }
  tr {
    border-bottom: 1px solid #e9e9e9;
  }
  tr.highlighted {
    animation: background-fade-highlight 2.5s ease-out;
  }
  ruby > rt {
    font-size: 72%;
  }
  article.post {
    border-bottom: 1px solid #ddd;
  }
  article.post img.avatar {
    border-radius: 50%;
  }
  article.post.deleted {
    background-color: #ffe5e5;
  }
  article.post .quote .title {
    border-left: 5px solid #dcdcdc;
    padding: 10px 10px 0 12px;
  }
  article.post .quote .title .avatar {
    margin-right: 7px;
  }
  article.post ol,
  article.post ul {
    clear: none;
  }
  article.post blockquote {
    padding: 10px 8px 10px 13px;
    margin: 0 0 10px 0;
    border-left: 5px solid #dcdcdc;
  }
  article.post blockquote p {
    margin: 0 0 10px 0;
  }
  article.post blockquote p:last-of-type {
    margin-bottom: 0;
  }
  article.post .post-date {
    float: right;
    color: #aaa;
    font-size: 0.8706em;
    margin: 10px 4px 0 0;
  }
  article.post .author {
    padding: 10px 5px;
    float: left;
  }
  article.post .author img {
    max-width: 45px;
    border-radius: 50%;
  }
  article.post .cooked {
    padding: 10px 0;
    margin-left: 65px;
    word-wrap: break-word;
    word-break: break-word;
  }
  article.post .cooked pre {
    white-space: pre-wrap;
  }
  article.post .cooked img {
    max-width: 100%;
    height: auto;
  }
  article.post .cooked p {
    margin: 0 0 1em 0;
  }
  .username {
    font-size: 0.8706em;
    margin: 0 0 10px 0;
  }
  .username a {
    color: #5c5c5c;

    &:visited,
    &:hover,
    &:active {
      color: #5c5c5c;
    }
  }
  .username span.title {
    font-weight: normal;
    color: #999;
  }
  .logo {
    float: right;
    max-height: 30px;
  }
  .replies {
    font-size: 1em;
    color: #999;
  }
  header.discourse {
    padding-left: 10px;
    padding-right: 10px;
    padding-bottom: 8px;
    font-size: 1.3195em;
    border-bottom: 3px solid #ddd;
    display: flex;
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
  }
  header.discourse h3 {
    margin: 0 auto 0 0;
    font-size: 1em;
  }
}
</style>
