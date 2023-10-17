<template>
  <ul class="pagination">
    <li ref="prev" :class="{ disabled: !hasPrevious }">
      <a @click="previous">«</a>
    </li>

    <template v-if="needJump.first">
      <li>
        <a @click="jump(1)">{{ 1 }}</a>
      </li>
      <li class="disabled">
        <a>...</a>
      </li>
    </template>

    <template v-for="i in buttonRange">
      <li :key="i" ref="buttons" :class="{ active: current === i }">
        <a @click="jump(i)">{{ i }}</a>
      </li>
    </template>

    <template v-if="needJump.last">
      <li class="disabled">
        <a>...</a>
      </li>
      <li ref="lastButton">
        <a @click="jump(total)">{{ total }}</a>
      </li>
    </template>

    <li :class="{ disabled: !hasNext }">
      <a @click="next">»</a>
    </li>
  </ul>
</template>

<script>
import range from 'lodash/range'

export default {
  props: {
    current: {
      type: Number,
      required: true,
    },
    total: {
      type: Number,
      required: true,
    },
    maxButtons: {
      type: Number,
      default: 15,
    },
  },
  data() {
    return {
      buttonWidth: 45,
      areaWidth: 800,
    }
  },
  computed: {
    hasPrevious() {
      return this.current - 1 >= 1
    },
    hasNext() {
      return this.current + 1 <= this.total
    },
    numButtons() {
      // Add a bit more just in case
      const buttonsInArea = this.areaWidth / (this.buttonWidth + 5)
      // Subtract 2 because the next and prev buttons
      return Math.min(this.maxButtons, this.total, Math.max(7, Math.floor(buttonsInArea) - 2))
    },
    leftSideButtons() {
      return Math.floor(this.numButtons / 2)
    },
    rightSideButtons() {
      // We round up here, so this is the side we "place" the active button on
      return Math.ceil(this.numButtons / 2) - 1
    },
    start() {
      return this.current - this.leftSideButtons
    },
    end() {
      return this.current + this.rightSideButtons
    },
    startOffset() {
      return this.start < 1 ? Math.abs(this.start) + 1 : 0
    },
    endOffset() {
      return this.end > this.total ? this.end - this.total : 0
    },
    needJump() {
      return {
        first: this.start - this.endOffset > 1,
        last: this.end + this.startOffset < this.total,
      }
    },
    buttonRange() {
      const start = this.start + this.startOffset - this.endOffset + this.needJump.first * 2
      const end = this.end + this.startOffset - this.endOffset - this.needJump.last * 2

      return range(start, end + 1)
    },
  },
  watch: {
    current() {
      this.$nextTick(() => this.resize())
    },
  },
  mounted() {
    window.addEventListener('resize', this.resize)
    this.resize()
  },
  destroyed() {
    window.removeEventListener('resize', this.resize)
  },
  methods: {
    previous() {
      if (this.hasPrevious) {
        this.$emit('prev')
      }
    },
    next() {
      if (this.hasNext) {
        this.$emit('next')
      }
    },
    jump(page) {
      if (page > 0 <= this.total) {
        this.$emit('jump-to', page)
      }
    },
    resize() {
      this.areaWidth = this.$parent.$el.clientWidth

      // We get the length of the last button to be on the safe side
      this.buttonWidth = Math.max(
        this.$refs.prev.offsetWidth,
        (this.$refs.buttons && this.$refs.buttons[this.$refs.buttons.length - 1]?.offsetWidth) ?? 0,
        this.$refs.lastButton?.offsetWidth ?? 0
      )
    },
  },
}
</script>

<style lang="scss">
@import './../scss/variables';

.pagination {
  display: flex;
  justify-content: center;

  > li {
    margin-right: 1rem;
    cursor: pointer;

    &:last-child {
      margin-right: 0;
    }

    &.disabled a,
    &.disabled a:hover {
      background: transparent;
      border: 1px solid #ddd;
      color: inherit;
    }

    a {
      border: 1px solid #ddd;
      padding: 0.85rem 1.6rem;
      background: #ffffff;
      color: $sponge_grey;

      &:first-child,
      &:last-child {
        border-radius: 0;
      }

      &:hover {
        border-color: #f7cf0d;
        background: #f7cf0d;
        color: #685603;
      }
    }

    &.active {
      > a,
      > a:hover {
        cursor: pointer;
        color: darken($sponge_yellow, 30);
      }
    }
  }
}
</style>
