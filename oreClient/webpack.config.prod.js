const { merge } = require('webpack-merge')
const TerserPlugin = require('terser-webpack-plugin')
const CSSMinimizerPlugin = require('css-minimizer-webpack-plugin')
const commonConfig = require('./webpack.config.common.js')

module.exports = merge(commonConfig, {
  mode: 'production',
  optimization: {
    minimizer: [new TerserPlugin(), new CSSMinimizerPlugin()],
  },
})
