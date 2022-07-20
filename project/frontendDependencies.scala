import sbt._

//noinspection TypeAnnotation
object NPMDeps {

  val vue                 = "vue"                   -> "2.6.14"
  val vueLoader           = "vue-loader"            -> "15.9.8"
  val vueTemplateCompiler = "vue-template-compiler" -> "2.6.14"
  val vueStyleLoader      = "vue-style-loader"      -> "4.1.3"

  val lodash      = "lodash"       -> "4.17.21"
  val queryString = "query-string" -> "7.1.1"

  val fontAwesome        = "@fortawesome/fontawesome-svg-core"   -> "6.1.1"
  val fontAwesomeSolid   = "@fortawesome/free-solid-svg-icons"   -> "6.1.1"
  val fontAwesomeRegular = "@fortawesome/free-regular-svg-icons" -> "6.1.1"
  val fontAwesomeBrands  = "@fortawesome/free-brands-svg-icons"  -> "6.1.1"

  val babel          = "@babel/core"       -> "7.18.9"
  val babelLoader    = "babel-loader"      -> "8.2.5"
  val babelPresetEnv = "@babel/preset-env" -> "7.18.9"

  val webpack               = "4.46.0"
  val webpackDevServer      = "3.11.3"
  val webpackMerge          = "webpack-merge" -> "5.8.0"
  val webpackTerser         = "terser-webpack-plugin" -> "4.2.3"
  val webpackCopy           = "copy-webpack-plugin" -> "6.4.0"
  val webpackBundleAnalyzer = "webpack-bundle-analyzer" -> "4.5.0"

  val postcss           = "postcss"                            -> "8.4.6"
  val cssLoader         = "css-loader"                         -> "5.2.7"
  val sassLoader        = "sass-loader"                        -> "10.2.1"
  val postCssLoader     = "postcss-loader"                     -> "4.3.0"
  val miniCssExtractor  = "mini-css-extract-plugin"            -> "1.6.2"
  val optimizeCssAssets = "optimize-css-assets-webpack-plugin" -> "6.0.1"
  val autoprefixer      = "autoprefixer"                       -> "10.4.7"
  val sass              = "sass"                               -> "1.53.0"
}

object WebjarsDeps {

  val jQuery      = "org.webjars.npm" % "jquery"       % "2.2.4"
  val fontAwesome = "org.webjars"     % "font-awesome" % "6.1.1"
  val filesize    = "org.webjars.npm" % "filesize"     % "9.0.1"
  val moment      = "org.webjars.npm" % "moment"       % "2.29.4"
  val clipboard   = "org.webjars.npm" % "clipboard"    % "2.0.11"
  val chartJs     = "org.webjars.npm" % "chart.js"     % "3.8.0"
  val swaggerUI   = "org.webjars"     % "swagger-ui"   % "4.11.1"
}
