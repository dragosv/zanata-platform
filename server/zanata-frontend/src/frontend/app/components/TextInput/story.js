import React from 'react'
import { storiesOf, action } from '@kadira/storybook'
import { TextInput } from '../'

storiesOf('TextInput', module)
    .add('default', () => (
      <TextInput
          maxLength={100}
          id='demo'
          className='textInput'
          placeholder='TextInput…'
          accessibilityLabel='TextInput'
          defaultValue='Default text'
          onKeyDown={action('keyDown')}
      />
    ))
