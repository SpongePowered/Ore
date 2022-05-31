const Path = require('path')
const {VueLoaderPlugin} = require('vue-loader')
const MiniCssExtractPlugin = require('mini-css-extract-plugin')
// const BundleAnalyzerPlugin = require('webpack-bundle-analyzer').BundleAnalyzerPlugin;

const rootDir = Path.resolve(__dirname)
const resourcesDir = Path.resolve(rootDir, 'src', 'main', 'assets')
const entryDir = Path.resolve(resourcesDir, 'entries')
const modulesDir = Path.resolve(__dirname, 'node_modules')

const outputDir =
  process.env.FROM_SBT === 'true'
    ? Path.resolve(__dirname, 'target', 'web', 'public', 'main', 'build')
    : Path.resolve(__dirname, 'dist')

module.exports = {
  entry: {
    'font-awesome': Path.resolve(entryDir, 'font-awesome.js'),
    main: Path.resolve(resourcesDir, 'scss', 'main.scss'),
    home: Path.resolve(entryDir, 'home.js'),
  },
  output: {
    path: outputDir,
    filename: '[name].js',
    libraryTarget: 'umd',
    publicPath: '/',
  },
  plugins: [
    new VueLoaderPlugin(),
    new MiniCssExtractPlugin(),
    // new BundleAnalyzerPlugin()
  ],
  module: {
    rules: [
      {
        test: /\.vue$/,
        loader: 'vue-loader',
      },
      {
        test: /\.js$/,
        loader: 'babel-loader',
        include: resourcesDir,
        options: {
          presets: ['@babel/preset-env'],
        },
      },
      {
        test: /\.css$/,
        use: [
          process.env.NODE_ENV !== 'production' && process.env.FROM_SBT !== 'true'
            ? 'vue-style-loader'
            : MiniCssExtractPlugin.loader,
          'css-loader',
          'postcss-loader',
        ],
      },
      {
        test: /\.scss$/,
        use: [
          process.env.NODE_ENV !== 'production' && process.env.FROM_SBT !== 'true'
            ? 'vue-style-loader'
            : MiniCssExtractPlugin.loader,
          'css-loader',
          'postcss-loader',
          'sass-loader',
        ],
      },
      {
        test: /\.json5$/i,
        loader: 'json5-loader',
        type: 'javascript/auto',
      },
      {
        test: /\.(png|jpe?g|gif|svg)$/i,
        use: [
          {
            loader: 'file-loader',
            options: {
              publicPath: process.env.FROM_SBT === 'true' ? '/assets/lib/ore-client' : undefined,
              esModule: false,
            },
          },
        ],
      },
    ],
  },
  resolve: {
    extensions: ['.js', '.vue', '.css'],
    alias: {
      vue$: 'vue/dist/vue.esm.js',
    },
    modules: [modulesDir],
  },
  devServer: {
    historyApiFallback: true,
  },
  optimization: {
    splitChunks: {
      cacheGroups: {
        vendors: {
          name: 'vendors',
          chunks: 'initial',
          test: /[\\/]node_modules[\\/]/,
          priority: 10,
          enforce: true,
        },
        commons: {
          name: 'commons',
          chunks: 'initial',
          minChunks: 2,
        },
      },
    },
  },
}
